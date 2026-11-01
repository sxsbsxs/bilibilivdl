package com.sxsbsxs.bilivdl.data
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
data class PlaybackInfo(val cid:Long,val url:String,val quality:Int,val name:String,val qualities:List<Pair<Int,String>>)
object BiliPlaybackApi{
 private fun get(url:String):JSONObject{
  val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=15000;c.readTimeout=20000
  c.setRequestProperty("User-Agent","Mozilla/5.0 Chrome/124 Safari/537.36");c.setRequestProperty("Referer","https://www.bilibili.com/")
  return try{JSONObject(c.inputStream.bufferedReader().use{it.readText()})}finally{c.disconnect()}
 }
 fun load(bvid:String,aid:Long,qn:Int=80):PlaybackInfo{
  val id=if(bvid.isNotBlank())"bvid="+URLEncoder.encode(bvid,"UTF-8") else "avid="+aid
  val root=get("https://api.bilibili.com/x/web-interface/view?"+id)
  if(root.optInt("code")!=0)error(root.optString("message","视频信息获取失败"))
  val d=root.getJSONObject("data");val cid=d.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")?:d.optLong("cid")
  if(cid<=0)error("未获取到视频分P信息")
  val p=get("https://api.bilibili.com/x/player/playurl?"+id+"&cid="+cid+"&qn="+qn+"&fnval=0&fourk=1")
  if(p.optInt("code")!=0)error(p.optString("message","播放地址获取失败，可能需要登录"))
  val data=p.getJSONObject("data");val url=data.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
  if(url.isBlank())error("接口未返回可播放直链；该视频可能需要登录或受地区限制")
  val ids=data.optJSONArray("accept_quality");val names=data.optJSONArray("accept_description");val qs=mutableListOf<Pair<Int,String>>()
  if(ids!=null&&names!=null)for(i in 0 until minOf(ids.length(),names.length()))qs.add(ids.optInt(i) to names.optString(i))
  val quality=data.optInt("quality",qn)
  return PlaybackInfo(cid,url,quality,qs.firstOrNull{it.first==quality}?.second?:"当前清晰度",qs)
 }
}
package com.sxsbsxs.bilivdl.data
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
data class VideoComment(val user:String,val text:String,val likes:Int)
object BiliInteractionApi{
 private fun request(url:String):String{val c=URL(url).openConnection() as HttpURLConnection;c.connectTimeout=12000;c.readTimeout=15000;c.setRequestProperty("User-Agent","Mozilla/5.0 Chrome/124 Safari/537.36");c.setRequestProperty("Referer","https://www.bilibili.com/");return try{c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}
 fun comments(aid:Long):List<VideoComment>{
  if(aid<=0)return emptyList()
  val r=JSONObject(request("https://api.bilibili.com/x/v2/reply?type=1&oid="+aid+"&pn=1&ps=20&sort=2"))
  if(r.optInt("code")!=0)error(r.optString("message","评论暂不可用"))
  val a=r.optJSONObject("data")?.optJSONArray("replies")?:return emptyList()
  return buildList{for(i in 0 until a.length()){val x=a.optJSONObject(i)?:continue;add(VideoComment(x.optJSONObject("member")?.optString("uname")?:"用户",x.optJSONObject("content")?.optString("message")?:"",x.optInt("like")))}}
 }
 fun danmaku(cid:Long):List<String>{
  val xml=request("https://api.bilibili.com/x/v1/dm/list.so?oid="+cid)
  return Regex("<d\\s+p=\"[^\"]+\">(.*?)</d>",setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).take(100).map{it.groupValues[1].replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")}.toList()
 }
}
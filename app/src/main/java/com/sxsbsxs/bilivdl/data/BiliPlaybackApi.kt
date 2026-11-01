package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap

data class PlaybackInfo(val cid: Long, val url: String, val quality: Int, val name: String, val qualities: List<Pair<Int, String>>, val isDash: Boolean = false, val videoUrl: String? = null, val audioUrl: String? = null)

object BiliPlaybackApi {
    private val order = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
    private fun get(url: String, cookie: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=20000; readTimeout=25000; instanceFollowRedirects=true
            setRequestProperty("User-Agent", BiliHeaders.UA); setRequestProperty("Referer","https://www.bilibili.com/")
            setRequestProperty("Origin","https://www.bilibili.com"); setRequestProperty("Accept","application/json, text/plain, */*")
            if(cookie.isNotBlank()) setRequestProperty("Cookie",cookie)
        }
        return try {
            val stream=if(c.responseCode in 200..299)c.inputStream else c.errorStream
            val body=stream?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(body.isBlank()) error("B站接口 HTTP ${c.responseCode}")
            JSONObject(body)
        } finally { c.disconnect() }
    }
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun md5(s:String)=MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun mixin(cookie:String):String {
        val nav=get("https://api.bilibili.com/x/web-interface/nav",cookie)
        if(nav.optInt("code")!=0) error("无法获取 B站签名密钥：${nav.optString("message")}")
        val img=nav.optJSONObject("data")?.optJSONObject("wbi_img")?:error("B站未返回 WBI 密钥")
        val a=img.optString("img_url").substringAfterLast('/').substringBefore('.')
        val b=img.optString("sub_url").substringAfterLast('/').substringBefore('.')
        val raw=a+b
        if(raw.length<64) error("B站 WBI 密钥格式异常")
        return order.joinToString(""){raw[it].toString()}.take(32)
    }
    private fun signed(path:String, values:Map<String,String>, cookie:String):String {
        val p=TreeMap(values); p["wts"]=(System.currentTimeMillis()/1000).toString()
        val query=p.entries.joinToString("&"){"${enc(it.key)}=${enc(it.value).replace(Regex("[!'()*]"),"")}"}
        return "https://api.bilibili.com$path?$query&w_rid=${md5(query+mixin(cookie))}"
    }
    fun load(bvid:String, aid:Long, qn:Int=80, cookie:String=""):PlaybackInfo {
        require(bvid.isNotBlank()||aid>0){"缺少 BV/AV 视频编号"}
        val id=if(bvid.isNotBlank()) mapOf("bvid" to bvid) else mapOf("aid" to aid.toString())
        val detail=get(signed("/x/web-interface/wbi/view",id,cookie),cookie)
        if(detail.optInt("code")!=0) {
            val code=detail.optInt("code")
            error(if(code == -352) "B站风控拦截视频详情请求（-352）。请在“我的”中登录账号后重试" else detail.optString("message","视频信息获取失败"))
        }
        val d=detail.optJSONObject("data")?:error("视频信息为空")
        val cid=d.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")?:d.optLong("cid")
        if(cid<=0) error("没有获取到视频分P信息")
        val params=mutableMapOf("cid" to cid.toString(),"qn" to qn.toString(),"fnver" to "0","fnval" to "4048","fourk" to "1")
        params.putAll(id)
        val play=get(signed("/x/player/wbi/playurl",params,cookie),cookie)
        if(play.optInt("code")!=0) {
            val code=play.optInt("code")
            error(if(code == -352) "B站风控拦截播放地址请求（-352）。请登录账号或稍后重试" else play.optString("message","B站拒绝提供播放地址"))
        }
        val data=play.optJSONObject("data")?:error("播放接口没有返回 data")
        val ids=data.optJSONArray("accept_quality"); val names=data.optJSONArray("accept_description")
        val qualities=mutableListOf<Pair<Int,String>>()
        if(ids!=null&&names!=null) for(i in 0 until minOf(ids.length(),names.length())) qualities.add(ids.optInt(i) to names.optString(i))
        val actual=data.optInt("quality",qn)
        val dash=data.optJSONObject("dash")
        if(dash!=null) {
            val videos=dash.optJSONArray("video") ?: error("DASH 视频轨道为空")
            val video=(0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.firstOrNull { it.optInt("id")==actual }
                ?: (0 until videos.length()).mapNotNull { videos.optJSONObject(it) }.firstOrNull() ?: error("没有可下载的视频轨道")
            val audios=dash.optJSONArray("audio") ?: error("DASH 音频轨道为空")
            val audio=(0 until audios.length()).mapNotNull { audios.optJSONObject(it) }.firstOrNull() ?: error("没有可下载的音频轨道")
            fun streamUrl(item: JSONObject): String {
                val primary=item.optString("baseUrl").ifBlank { item.optString("base_url") }
                val backups=item.optJSONArray("backupUrl") ?: item.optJSONArray("backup_url")
                return primary.ifBlank { backups?.optString(0).orEmpty() }.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?: error("B站返回了无效的 DASH 资源地址")
            }
            return PlaybackInfo(cid,DashManifestBuilder.toDataUri(dash,actual),actual,qualities.firstOrNull{it.first==actual}?.second?:"当前清晰度",qualities,true,streamUrl(video),streamUrl(audio))
        }
        val stream=data.optJSONArray("durl")?.optJSONObject(0)?.optString("url").orEmpty()
        if(stream.isBlank()) error("B站未返回可播放的 DASH 或 MP4 音视频流")
        return PlaybackInfo(cid,stream,actual,qualities.firstOrNull{it.first==actual}?.second?:"当前清晰度",qualities,false)
    }
}

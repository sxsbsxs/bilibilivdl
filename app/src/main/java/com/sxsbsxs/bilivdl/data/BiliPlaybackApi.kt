package com.sxsbsxs.bilivdl.data

import bilibili.app.playurl.v1.PlayURLGRPC
import bilibili.app.playurl.v1.PlayViewReq
import bilibili.app.playurl.v1.Stream
import com.a10miaomiao.bilimiao.comm.network.BiliGRPCHttp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap

data class PlaybackInfo(val cid: Long, val url: String, val quality: Int, val name: String, val qualities: List<Pair<Int, String>>, val isDash: Boolean = false, val videoUrl: String? = null, val audioUrl: String? = null, val segments: List<String> = emptyList())

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


    /**
     * gRPC 优先的匿名播放线路。gRPC 不可用时返回 null，由调用方继续 HTTP 备用线路。
     */
    private suspend fun tryGrpcPlayback(aid: Long, cid: Long, requestedQuality: Int): PlaybackInfo? {
        return try {
            val request = PlayViewReq(
                aid = aid,
                cid = cid,
                qn = requestedQuality.toLong(),
                download = 0,
                fnval = 4048,
                fnver = 0,
                forceHost = 2,
                fourk = true
            )
            val response = BiliGRPCHttp.request { PlayURLGRPC.playView(request) }.awaitCall()
            val videoInfo = response.videoInfo ?: return null
            val streams = videoInfo.streamList.filter { it.content != null }
            if (streams.isEmpty()) return null
            val selected = streams.firstOrNull { it.streamInfo?.quality == requestedQuality }
                ?: streams.firstOrNull()
                ?: return null
            val actualQuality = selected.streamInfo?.quality ?: videoInfo.quality
            val qualities = streams.mapNotNull { stream ->
                stream.streamInfo?.let { it.quality to it.newDescription }
            }.distinctBy { it.first }
            when (val content = selected.content ?: return null) {
                is Stream.Content.DashVideo -> {
                    val dash = content.value
                    val videoUrl = dash.baseUrl.ifBlank { dash.backupUrl.firstOrNull().orEmpty() }
                    if (videoUrl.isBlank()) return null
                    val audio = videoInfo.dashAudio.firstOrNull {
                        it.id == dash.audioId && it.baseUrl.isNotBlank()
                    } ?: videoInfo.dashAudio.firstOrNull { it.baseUrl.isNotBlank() }
                    val audioUrl = audio?.baseUrl?.ifBlank { audio.backupUrl.firstOrNull().orEmpty() }
                    PlaybackInfo(
                        cid = cid,
                        url = videoUrl,
                        quality = actualQuality,
                        name = qualities.firstOrNull { it.first == actualQuality }?.second ?: "当前清晰度",
                        qualities = qualities,
                        isDash = audioUrl?.isNotBlank() == true,
                        videoUrl = videoUrl,
                        audioUrl = audioUrl?.takeIf { it.isNotBlank() }
                    )
                }
                is Stream.Content.SegmentVideo -> {
                    val segments = content.value.segment.mapNotNull { segment ->
                        segment.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    }
                    if (segments.isEmpty()) return null
                    PlaybackInfo(
                        cid = cid,
                        url = segments.first(),
                        quality = actualQuality,
                        name = qualities.firstOrNull { it.first == actualQuality }?.second ?: "当前清晰度",
                        qualities = qualities,
                        segments = segments
                    )
                }
            }
        } catch (_: Exception) {
            // 某些网络环境可能无法访问 gRPC；不可因此阻断匿名 HTTP 播放。
            null
        }
    }

    suspend fun load(bvid:String, aid:Long, qn:Int=80, cookie:String=""):PlaybackInfo {
        require(bvid.isNotBlank()||aid>0){"缺少 BV/AV 视频编号"}
        val id=if(bvid.isNotBlank()) mapOf("bvid" to bvid) else mapOf("aid" to aid.toString())
        // bilimiao 使用免登录的普通视频详情接口；先走公开接口，避免把 WBI/登录状态当作播放前置条件。
        val detailUrl = "https://api.bilibili.com/x/web-interface/view?" +
            id.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        var detail = get(detailUrl, cookie)
        if (detail.optInt("code") != 0) {
            // 兼容 B 站对公开详情接口的风控：最后再尝试 WBI 详情接口。
            detail = get(signed("/x/web-interface/wbi/view", id, cookie), cookie)
        }
        if(detail.optInt("code")!=0) {
            val code=detail.optInt("code")
            error(if(code == -352) "B站暂时风控了视频详情请求（-352），可稍后重试；普通播放不要求登录" else detail.optString("message","视频信息获取失败"))
        }
        val d=detail.optJSONObject("data")?:error("视频信息为空")
        val cid=d.optJSONArray("pages")?.optJSONObject(0)?.optLong("cid")?:d.optLong("cid")
        if(cid<=0) error("没有获取到视频分P信息")
        val params=mutableMapOf("cid" to cid.toString(),"qn" to qn.toString(),"fnver" to "0","fnval" to "4048","fourk" to "1")
        params.putAll(id)
        // 采用 bilimiao 的免登录播放接口：/x/player/playurl + avid/cid/qn/fnval。
        // 不把 Cookie 或 WBI 签名作为必需条件；登录 Cookie 仅作为可选请求头。
        val resolvedAid = d.optLong("aid", aid)
        // bilimiao2 同款：先尝试 gRPC PlayURL；不可用时自动回退公开 HTTP playurl。
        tryGrpcPlayback(resolvedAid, cid, qn)?.let { return it }
        val publicParams = mutableMapOf(
            "avid" to resolvedAid.toString(),
            "cid" to cid.toString(),
            "qn" to qn.toString(),
            "fnval" to "4048",
            "fnver" to "0",
            "force_host" to "2",
            "type" to "",
            "otype" to "json",
            "fourk" to "1"
        )
        val publicQuery = publicParams.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        var play = get("https://api.bilibili.com/x/player/playurl?$publicQuery", cookie)
        if (play.optInt("code") != 0) {
            // 公开旧接口被临时限制时，回退到 WBI playurl；匿名状态仍然先尝试免登录线路。
            play = get(signed("/x/player/wbi/playurl", params, cookie), cookie)
        }
        if(play.optInt("code")!=0) {
            val code=play.optInt("code")
            error(if(code == -352) "B站暂时风控了免登录播放地址请求（-352），请稍后重试；如需更高画质再扫码登录" else play.optString("message","B站拒绝提供播放地址"))
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
        val durls = data.optJSONArray("durl")
        val streams = durls?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val primary = item.optString("url")
                val backups = item.optJSONArray("backup_url") ?: item.optJSONArray("backupUrl")
                val selected = primary.ifBlank { backups?.optString(0).orEmpty() }
                selected.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            }
        }.orEmpty()
        if(streams.isEmpty()) error("B站未返回可播放的 DASH 或 MP4 音视频流")
        // DURL 可能由多个连续分段组成；不能只取第一个分段，否则播放/下载会被截断。
        return PlaybackInfo(
            cid, streams.first(), actual,
            qualities.firstOrNull{it.first==actual}?.second?:"当前清晰度",
            qualities, false, segments = streams
        )
    }
}

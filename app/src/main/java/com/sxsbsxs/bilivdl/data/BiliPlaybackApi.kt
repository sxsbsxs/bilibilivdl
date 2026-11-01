package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class PlaybackInfo(val cid: Long, val url: String, val quality: Int, val name: String, val qualities: List<Pair<Int, String>>)

object BiliPlaybackApi {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 25000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", "https://www.bilibili.com/")
        c.setRequestProperty("Origin", "https://www.bilibili.com")
        c.setRequestProperty("Accept", "application/json, text/plain, */*")
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("B站接口 HTTP ${c.responseCode}")
            JSONObject(body)
        } finally { c.disconnect() }
    }
    fun load(bvid: String, aid: Long, qn: Int = 80): PlaybackInfo {
        require(bvid.isNotBlank() || aid > 0) { "缺少 BV/AV 视频编号" }
        val id = if (bvid.isNotBlank()) "bvid=" + URLEncoder.encode(bvid, "UTF-8") else "avid=$aid"
        val root = get("https://api.bilibili.com/x/web-interface/view?$id")
        if (root.optInt("code") != 0) error(root.optString("message", "视频信息获取失败"))
        val d = root.getJSONObject("data")
        val pages = d.optJSONArray("pages")
        val cid = pages?.optJSONObject(0)?.optLong("cid") ?: d.optLong("cid")
        if (cid <= 0) error("没有获取到视频分P信息")
        val play = get("https://api.bilibili.com/x/player/playurl?$id&cid=$cid&qn=$qn&fnval=0&fourk=1&platform=html5")
        if (play.optInt("code") != 0) error(play.optString("message", "B站拒绝提供播放地址；可能需要登录"))
        val data = play.optJSONObject("data") ?: error("播放接口没有返回 data")
        val durl = data.optJSONArray("durl")
        val streamUrl = durl?.optJSONObject(0)?.optString("url").orEmpty()
        if (streamUrl.isBlank()) error("没有获取到可用的视频直链；可能需要登录或该视频不支持当前播放方式")
        val ids = data.optJSONArray("accept_quality")
        val names = data.optJSONArray("accept_description")
        val qualities = mutableListOf<Pair<Int, String>>()
        if (ids != null && names != null) {
            for (i in 0 until minOf(ids.length(), names.length())) qualities.add(ids.optInt(i) to names.optString(i))
        }
        val actual = data.optInt("quality", qn)
        return PlaybackInfo(cid, streamUrl, actual, qualities.firstOrNull { it.first == actual }?.second ?: "当前清晰度", qualities)
    }
}
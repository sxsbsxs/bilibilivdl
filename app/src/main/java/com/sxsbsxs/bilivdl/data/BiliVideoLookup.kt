package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BiliVideoLookup {
    fun get(input: String): BiliVideo {
        val id = BiliApi.resolveVideoId(input) ?: error("请输入有效的 BV 号或 av 号")
        val query = if (id.startsWith("av")) "aid=${id.drop(2)}" else "bvid=$id"
        val connection = (URL("https://api.bilibili.com/x/web-interface/view?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("视频详情接口没有返回内容（HTTP ${connection.responseCode}）")
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) error(root.optString("message").ifBlank { "无法获取该视频" })
            val data = root.optJSONObject("data") ?: error("视频详情数据为空")
            val stat = data.optJSONObject("stat")
            val owner = data.optJSONObject("owner")
            val cover = data.optString("pic").let { if (it.startsWith("//")) "https:$it" else it }
            val seconds = data.optInt("duration")
            return BiliVideo(
                title = data.optString("title").replace(Regex("<[^>]*>"), ""),
                cover = cover,
                author = owner?.optString("name") ?: "未知 UP 主",
                play = stat?.optString("view") ?: "0",
                duration = "%d:%02d".format(seconds / 60, seconds % 60),
                bvid = data.optString("bvid"),
                aid = data.optLong("aid"),
                description = data.optString("desc"),
                danmaku = stat?.optLong("danmaku"),
                likes = stat?.optLong("like"),
                favorites = stat?.optLong("favorite"),
                coins = stat?.optLong("coin"),
                shares = stat?.optLong("share"),
                comments = stat?.optLong("reply"),
                category = data.optString("tname"),
                pubdate = data.optLong("pubdate").takeIf { it > 0L },
                authorFace = owner?.optString("face").orEmpty().let { if (it.startsWith("//")) "https:$it" else it }
            )
        } finally {
            connection.disconnect()
        }
    }
}
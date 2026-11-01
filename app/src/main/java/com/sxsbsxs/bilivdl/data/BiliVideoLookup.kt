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
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.bilibili.com/")
        }
        try {
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (root.optInt("code", -1) != 0) error(root.optString("message", "无法获取该视频"))
            val data = root.getJSONObject("data")
            val cover = data.optString("pic").let { if (it.startsWith("//")) "https:$it" else it }
            return BiliVideo(
                title = data.optString("title"),
                cover = cover,
                author = data.optJSONObject("owner")?.optString("name") ?: "未知 UP 主",
                play = data.optJSONObject("stat")?.optString("view") ?: "0",
                duration = data.optInt("duration").let { seconds -> "%d:%02d".format(seconds / 60, seconds % 60) },
                bvid = data.optString("bvid"),
                aid = data.optLong("aid"),
                description = data.optString("desc")
            )
        } finally {
            connection.disconnect()
        }
    }
}
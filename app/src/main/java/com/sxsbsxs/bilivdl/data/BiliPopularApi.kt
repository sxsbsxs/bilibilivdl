package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BiliPopularApi {
    fun loadPopular(): List<BiliVideo> {
        val connection = (URL("https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.bilibili.com/")
        }
        try {
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (root.optInt("code", -1) != 0) error(root.optString("message", "热门视频暂不可用"))
            val items = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
            return buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val stat = item.optJSONObject("stat")
                    val owner = item.optJSONObject("owner")
                    val cover = item.optString("pic").let { if (it.startsWith("//")) "https:$it" else it }
                    val seconds = item.optInt("duration")
                    add(BiliVideo(
                        title = item.optString("title").replace(Regex("<[^>]*>"), ""),
                        cover = cover,
                        author = owner?.optString("name") ?: "未知 UP 主",
                        play = stat?.optString("view") ?: "0",
                        duration = "%d:%02d".format(seconds / 60, seconds % 60),
                        bvid = item.optString("bvid"),
                        aid = item.optLong("aid"),
                        description = item.optString("desc")
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
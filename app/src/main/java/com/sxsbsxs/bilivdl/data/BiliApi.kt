package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object BiliApi {
    fun searchVideos(keyword: String): List<BiliVideo> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = URL("https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=$encoded&page=1")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) {
                throw IllegalStateException(root.optString("message", "搜索接口暂不可用"))
            }
            val items = root.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
            return buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val title = item.optString("title").replace(Regex("<[^>]*>"), "")
                    add(BiliVideo(
                        title = title,
                        cover = item.optString("pic").let { if (it.startsWith("//")) "https:$it" else it },
                        author = item.optString("author", "未知 UP 主"),
                        play = item.optString("play", "0"),
                        duration = item.optString("duration", ""),
                        bvid = item.optString("bvid", ""),
                        aid = item.optLong("aid"),
                        description = item.optString("description", ""),
                        danmaku = item.optLong("video_review").takeIf { item.has("video_review") },
                        favorites = item.optLong("favorites").takeIf { item.has("favorites") },
                        category = item.optString("typename"),
                        pubdate = item.optLong("pubdate").takeIf { item.has("pubdate") },
                        authorFace = item.optString("upic").let { if (it.startsWith("//")) "https:$it" else it }
                    ))
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun resolveVideoId(input: String): String? {
        val value = input.trim()
        val bv = Regex("(?i)BV[0-9A-Za-z]{10}").find(value)?.value
        if (bv != null) return bv
        val av = Regex("(?i)av\\s*(\\d+)").find(value)?.groupValues?.getOrNull(1)
        return av?.let { "av$it" }
    }
}
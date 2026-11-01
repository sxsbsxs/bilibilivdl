package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object BiliPopularApi {
    fun loadPopular(page: Int = 1, cookie: String = ""): List<BiliVideo> {
        val connection = (URL("https://api.bilibili.com/x/web-interface/popular?ps=20&pn=$page").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
        }
        try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("热门推荐接口没有返回内容（HTTP ${connection.responseCode}）")
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) {
                error(root.optString("message", "热门视频暂不可用"))
            }
            val items = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
            val videos = buildList {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val stat = item.optJSONObject("stat")
                    val owner = item.optJSONObject("owner")
                    val seconds = item.optInt("duration")
                    val bvid = item.optString("bvid")
                    if (bvid.isBlank()) continue
                    add(
                        BiliVideo(
                            title = item.optString("title").replace(Regex("<[^>]*>"), ""),
                            cover = item.optString("pic").let { if (it.startsWith("//")) "https:$it" else it },
                            author = owner?.optString("name") ?: "未知 UP 主",
                            play = stat?.optString("view") ?: "0",
                            duration = "%d:%02d".format(seconds / 60, seconds % 60),
                            bvid = bvid,
                            aid = item.optLong("aid"),
                            description = item.optString("desc"),
                            danmaku = stat?.optLong("danmaku"),
                            likes = stat?.optLong("like"),
                            favorites = stat?.optLong("favorite"),
                            coins = stat?.optLong("coin"),
                            shares = stat?.optLong("share"),
                            comments = stat?.optLong("reply"),
                            category = item.optString("tname"),
                            pubdate = item.optLong("pubdate")
                        )
                    )
                }
            }
            // Keep page boundaries intact, but vary the order on each fetch/refresh.
            return videos.shuffled(Random(System.nanoTime() xor (page.toLong() shl 32)))
        } finally {
            connection.disconnect()
        }
    }
}
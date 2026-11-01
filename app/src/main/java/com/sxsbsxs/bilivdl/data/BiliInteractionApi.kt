package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VideoComment(val user: String, val text: String, val likes: Int)

object BiliInteractionApi {
    private fun request(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 18000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        c.setRequestProperty("Referer", "https://www.bilibili.com/")
        return try {
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally { c.disconnect() }
    }
    fun comments(aid: Long): List<VideoComment> {
        if (aid <= 0) return emptyList()
        val unique = linkedMapOf<String, VideoComment>()
        // 按时间顺序翻页，避免只显示热门接口返回的少量置顶评论。
        for (page in 1..3) {
            val root = JSONObject(request("https://api.bilibili.com/x/v2/reply?type=1&oid=$aid&pn=$page&ps=20&sort=0"))
            if (root.optInt("code") != 0) {
                if (page == 1) error(root.optString("message", "评论暂不可用"))
                break
            }
            val arr = root.optJSONObject("data")?.optJSONArray("replies") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val member = item.optJSONObject("member")
                val content = item.optJSONObject("content")
                val id = item.optString("rpid", "$page-$i")
                unique[id] = VideoComment(member?.optString("uname") ?: "用户", content?.optString("message") ?: "", item.optInt("like"))
            }
            if (unique.size >= 60) break
        }
        return unique.values.toList()
    }
    fun danmaku(cid: Long): List<String> {
        val xml = request("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid")
        return Regex("<d\\s+p=\"[^\"]+\">(.*?)</d>", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(xml).take(100).map {
                it.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            }.toList()
    }
}
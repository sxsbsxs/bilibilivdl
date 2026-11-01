package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Video interaction endpoints adapted from the API patterns used by bilimiao2.
 * Upstream: https://github.com/10miaomiao/bilimiao2
 * Copyright and licensing: GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md.
 * Adapted for BiliVdl on 2026-11-01.
 */
object BiliVideoActionApi {
    private const val APP_KEY = "dfca71928277209b"
    private const val APP_SECRET = "b5475a8825547a4fc26c7d518eaaa02e"
    private const val BUILD = "1450000"

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun signedParams(values: Map<String, String>): LinkedHashMap<String, String> {
        val params = linkedMapOf(
            "appkey" to APP_KEY,
            "build" to BUILD,
            "channel" to "bili",
            "c_locale" to "zh_CN",
            "mobi_app" to "android_hd",
            "platform" to "android",
            "s_locale" to "zh_CN",
            "statistics" to """{"appId":5,"platform":3,"version":"1.45.0","abtest":""}""",
            "ts" to (System.currentTimeMillis() / 1000).toString()
        )
        params.putAll(values)
        val canonical = params.filterValues { it.isNotBlank() }
            .map { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            .sorted().joinToString("&")
        params["sign"] = md5(canonical + APP_SECRET)
        return params
    }

    private fun call(
        path: String,
        values: Map<String, String>,
        cookie: String,
        post: Boolean = true,
        appApi: Boolean = true
    ): JSONObject {
        if (cookie.isBlank()) error("此操作需要先登录 B站账号")
        val params = if (appApi) signedParams(values) else values
        val query = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val endpoint = (if (appApi) "https://app.bilibili.com" else "https://api.bilibili.com") + path
        val url = if (post) endpoint else "$endpoint?$query"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = if (post) "POST" else "GET"
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (post) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        return try {
            if (post) {
                val body = params.entries.joinToString("&") {
                    "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isBlank()) error("B站互动接口 HTTP ${connection.responseCode}")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun check(root: JSONObject, fallback: String) {
        val code = root.optInt("code", -1)
        if (code != 0) {
            val message = root.optString("message").ifBlank { fallback }
            error(when (code) {
                -101 -> "账号未登录，请重新登录 B站"
                -352, -412 -> "B站风控拦截（$code）：$message"
                else -> "$message（$code）"
            })
        }
    }

    /** 点赞或取消点赞；与 bilimiao VideoAPI.like 使用相同接口语义。 */
    fun like(aid: Long, liked: Boolean, cookie: String) {
        val root = call(
            "/x/v2/view/like",
            mapOf("aid" to aid.toString(), "dislike" to "0", "like" to if (liked) "1" else "2"),
            cookie
        )
        check(root, "视频点赞失败")
    }

    /** 投币 1 或 2 枚；可选同时点赞。 */
    fun coin(aid: Long, count: Int, alsoLike: Boolean, cookie: String) {
        require(count in 1..2) { "每次最多投 2 枚硬币" }
        val root = call(
            "/x/v2/view/coin/add",
            mapOf("aid" to aid.toString(), "multiply" to count.toString(), "select_like" to if (alsoLike) "1" else "0"),
            cookie
        )
        check(root, "投币失败")
    }

    /** 一键三连。 */
    fun triple(aid: Long, cookie: String) {
        val root = call("/x/v2/view/like/triple", mapOf("aid" to aid.toString()), cookie)
        check(root, "一键三连失败")
    }

    /** 读取当前账号收藏夹，返回 (media_id, title)。 */
    fun favoriteFolders(cookie: String): List<Pair<Long, String>> {
        val nav = (URL("https://api.bilibili.com/x/web-interface/nav").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Cookie", cookie)
        }
        val mid = try {
            val body = nav.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            check(root, "获取账号信息失败")
            root.optJSONObject("data")?.optLong("mid") ?: 0L
        } finally { nav.disconnect() }
        if (mid <= 0) error("无法获取当前账号 UID")
        val root = call(
            "/x/v3/fav/folder/created/list-all",
            mapOf("up_mid" to mid.toString(), "type" to "2", "rid" to "0", "web_location" to "333.1387"),
            cookie,
            post = false,
            appApi = false
        )
        check(root, "获取收藏夹失败")
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optLong("id")
            if (id <= 0) null else id to item.optString("title", "收藏夹")
        }
    }

    /** 添加视频到指定收藏夹。 */
    fun addFavorite(aid: Long, mediaId: Long, cookie: String) {
        val csrf = Regex("bili_jct=([^;]+)").find(cookie)?.groupValues?.getOrNull(1)
            ?: error("登录 Cookie 缺少 bili_jct，无法收藏")
        val root = call(
            "/x/v3/fav/resource/deal",
            mapOf(
                "rid" to aid.toString(),
                "type" to "2",
                "add_media_ids" to mediaId.toString(),
                "del_media_ids" to "",
                "platform" to "web",
                "csrf" to csrf
            ),
            cookie,
            appApi = false
        )
        check(root, "收藏失败")
    }

    /** 发送弹幕，时间单位毫秒，mode: 1滚动/4底部/5顶部。 */
    fun sendDanmaku(aid: Long, cid: Long, progressMs: Long, message: String, cookie: String, color: Int = 0xFFFFFF, mode: Int = 1) {
        require(message.isNotBlank()) { "弹幕内容不能为空" }
        require(message.length <= 100) { "弹幕不能超过 100 个字符" }
        val csrf = Regex("bili_jct=([^;]+)").find(cookie)?.groupValues?.getOrNull(1)
            ?: error("登录 Cookie 缺少 bili_jct，无法发送弹幕")
        val root = call(
            "/x/v2/dm/post",
            mapOf(
                "msg" to message,
                "type" to "1",
                "oid" to cid.toString(),
                "aid" to aid.toString(),
                "progress" to progressMs.coerceAtLeast(0).toString(),
                "color" to color.toString(),
                "fontsize" to "25",
                "pool" to "0",
                "mode" to mode.toString(),
                "rnd" to System.currentTimeMillis().toString(),
                "csrf" to csrf
            ),
            cookie,
            appApi = false
        )
        check(root, "弹幕发送失败")
    }
}

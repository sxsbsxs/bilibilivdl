package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BiliQrCode(val url: String, val key: String)
data class BiliQrPoll(val code: Int, val message: String, val cookie: String)

object BiliQrLoginApi {
    private fun request(url: String): Pair<JSONObject, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "GET"
            useCaches = false
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://passport.bilibili.com/")
            setRequestProperty("Origin", "https://passport.bilibili.com")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
        return try {
            val httpCode = connection.responseCode
            val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("B站登录接口没有返回内容（HTTP $httpCode）")

            // Match BiliDownload: collect every Set-Cookie header from the successful poll.
            // Deduplicate by cookie name rather than the whole name/value pair.
            val cookieHeaders = connection.headerFields.entries
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value.orEmpty() }
            val cookies = cookieHeaders
                .map { it.substringBefore(';').trim() }
                .filter { it.contains('=') }
                .distinctBy { it.substringBefore('=').trim() }
                .joinToString("; ")
            JSONObject(body) to cookies
        } finally {
            connection.disconnect()
        }
    }

    fun generate(): BiliQrCode {
        val (root, _) = request("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
        if (root.optInt("code", -1) != 0) {
            error(root.optString("message").ifBlank { "二维码生成失败" })
        }
        val data = root.optJSONObject("data") ?: error("B站没有返回二维码数据")
        val url = data.optString("url")
        val key = data.optString("qrcode_key")
        if (url.isBlank() || key.isBlank()) error("二维码数据不完整")
        return BiliQrCode(url, key)
    }

    fun poll(key: String): BiliQrPoll {
        val encoded = URLEncoder.encode(key, "UTF-8")
        val (root, cookie) = request(
            "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$encoded"
        )
        val data = root.optJSONObject("data")
        return BiliQrPoll(
            code = data?.optInt("code") ?: root.optInt("code", -1),
            message = data?.optString("message").orEmpty().ifBlank {
                root.optString("message").ifBlank { "等待扫码" }
            },
            cookie = cookie
        )
    }
}

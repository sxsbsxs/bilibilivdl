package com.sxsbsxs.bilivdl.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object BiliSession {
    private const val PREFS = "bili_account"
    fun cookie(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("cookie", "").orEmpty().trim()
    fun saveCookie(context: Context, value: String) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("cookie", value.trim()).apply() }
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    fun profile(context: Context): JSONObject {
        val c = (URL("https://api.bilibili.com/x/web-interface/nav").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("User-Agent", BiliHeaders.UA); setRequestProperty("Referer", "https://www.bilibili.com/")
            cookie(context).takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
        }
        return try {
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (root.optInt("code") != 0) error(root.optString("message", "账号状态获取失败"))
            root.optJSONObject("data") ?: JSONObject()
        } finally { c.disconnect() }
    }
}
object BiliHeaders {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}

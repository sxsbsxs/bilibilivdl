package com.sxsbsxs.bilivdl.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import cc.kafuu.bilidownload.common.manager.AccountManager
import android.webkit.CookieManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BiliAccount(
    val mid: Long, val username: String, val avatar: String, val sign: String,
    val isLogin: Boolean, val level: Int, val vipType: Int, val vipStatus: Int
)

object BiliSession {
    private const val PREFS = "bili_account"
    private const val ENCRYPTED_COOKIE = "cookie_enc"
    private const val LEGACY_COOKIE = "cookie"
    private const val KEY_ALIAS = "bilivdl_session_cookie_v1"
    private const val GCM_TAG_BITS = 128

    @Synchronized fun cookie(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(ENCRYPTED_COOKIE, null)
        if (!encrypted.isNullOrBlank()) return runCatching { decrypt(encrypted) }.getOrDefault("")
        val legacy = prefs.getString(LEGACY_COOKIE, "").orEmpty().trim()
        if (legacy.isNotBlank()) {
            runCatching { saveCookie(context, legacy) }
            return legacy
        }
        return ""
    }

    @Synchronized fun saveCookie(context: Context, value: String) {
        val normalized = normalizeCookie(value)
        require(hasRequiredCookies(normalized)) { "登录凭据不完整，拒绝保存" }
        val encrypted = encrypt(normalized)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENCRYPTED_COOKIE, encrypted).remove(LEGACY_COOKIE).apply()
        val manager = CookieManager.getInstance()
        listOf("https://bilibili.com", "https://www.bilibili.com", "https://api.bilibili.com",
            "https://passport.bilibili.com", "https://m.bilibili.com")
            .forEach { manager.setCookie(it, normalized) }
        manager.flush()

        // Keep the embedded BiliDownload account header and detail screens in the
        // same login session as BiliVdl immediately, without requiring an app restart.
        val syncAccount = Runnable { AccountManager.updateCookie(normalized) }
        if (Looper.myLooper() == Looper.getMainLooper()) syncAccount.run()
        else Handler(Looper.getMainLooper()).post(syncAccount)
    }

    @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        // Remove BiliDownload's separate account preference as well; keep its Room
        // download history untouched.
        runCatching { AccountManager.clearLocalSession() }
    }

    /** Account identity model adapted from BiliDownload's AccountManager/BiliAccountModel flow. */
    fun account(context: Context, cookieOverride: String? = null): BiliAccount {
        val data = profile(context, cookieOverride)
        val level = data.optJSONObject("level_info")
        return BiliAccount(
            mid = data.optLong("mid"), username = data.optString("uname", "B站用户"),
            avatar = normalizeImageUrl(data.optString("face")), sign = data.optString("sign"),
            isLogin = data.optBoolean("isLogin"), level = level?.optInt("current_level") ?: 0,
            vipType = data.optInt("vipType"), vipStatus = data.optInt("vipStatus")
        )
    }

    fun profile(context: Context, cookieOverride: String? = null): JSONObject {
        val activeCookie = normalizeCookie(cookieOverride?.trim()?.takeIf { it.isNotBlank() } ?: cookie(context))
        val connection = (URL("https://api.bilibili.com/x/web-interface/nav").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000; requestMethod = "GET"
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (activeCookie.isNotBlank()) setRequestProperty("Cookie", activeCookie)
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("账号接口没有返回内容（HTTP $code）")
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) error(root.optString("message").ifBlank { "账号状态获取失败" })
            root.optJSONObject("data") ?: error("账号资料为空")
        } finally { connection.disconnect() }
    }

    /** Same server-side logout endpoint used by BiliDownload; only clear local state after success. */
    fun logout(context: Context) {
        val activeCookie = cookie(context)
        require(activeCookie.isNotBlank()) { "当前没有已保存的登录凭据" }
        val csrf = Regex("(?:^|;\\s*)bili_jct=([^;]+)").find(activeCookie)?.groupValues?.getOrNull(1)
            ?: error("登录凭据缺少 bili_jct，无法安全退出")
        val connection = (URL("https://passport.bilibili.com/login/exit/v2").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000; requestMethod = "POST"; doOutput = true
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Origin", "https://www.bilibili.com")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Cookie", activeCookie)
        }
        try {
            val payload = "biliCSRF=" + URLEncoder.encode(csrf, "UTF-8")
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val httpCode = connection.responseCode
            val stream = if (httpCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("退出接口没有返回内容（HTTP $httpCode）")
            val response = JSONObject(body)
            if (response.optInt("code", -1) != 0) error(response.optString("message").ifBlank { "B站拒绝退出登录" })
            clear(context)
        } finally { connection.disconnect() }
    }

    fun hasRequiredCookies(value: String): Boolean =
        value.contains("SESSDATA=") && value.contains("DedeUserID=") && value.contains("bili_jct=")

    private fun normalizeCookie(value: String) = value.split(';').map { it.trim() }
        .filter { it.contains('=') }.distinctBy { it.substringBefore('=').trim() }.joinToString("; ")

    private fun normalizeImageUrl(value: String) = if (value.startsWith("//")) "https:$value" else value

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return Base64.encodeToString(iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12) { "加密凭据格式无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(0, 12)))
        return cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
    }
}

object BiliHeaders {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}

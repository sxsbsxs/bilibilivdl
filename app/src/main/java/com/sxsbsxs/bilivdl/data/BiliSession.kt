package com.sxsbsxs.bilivdl.data

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

object BiliSession {
    private const val PREFS = "bili_account"
    private const val ENCRYPTED_COOKIE = "cookie_enc"
    private const val LEGACY_COOKIE = "cookie"
    private const val KEY_ALIAS = "bilivdl_session_cookie_v1"
    private const val GCM_TAG_BITS = 128

    /** Cookie storage is encrypted with an Android Keystore AES-GCM key. */
    @Synchronized
    fun cookie(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(ENCRYPTED_COOKIE, null)
        if (!encrypted.isNullOrBlank()) {
            return runCatching { decrypt(encrypted) }.getOrDefault("")
        }
        // One-time migration from earlier plaintext builds.
        val legacy = prefs.getString(LEGACY_COOKIE, "").orEmpty().trim()
        if (legacy.isNotBlank()) {
            runCatching { saveCookie(context, legacy) }
            return legacy
        }
        return ""
    }

    @Synchronized
    fun saveCookie(context: Context, value: String) {
        val cookie = value.trim()
        require(cookie.contains("SESSDATA=") && cookie.contains("DedeUserID=") && cookie.contains("bili_jct=")) {
            "登录凭据不完整，拒绝保存"
        }
        val encrypted = encrypt(cookie)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENCRYPTED_COOKIE, encrypted)
            .remove(LEGACY_COOKIE)
            .apply()
        val manager = CookieManager.getInstance()
        listOf(
            "https://bilibili.com",
            "https://www.bilibili.com",
            "https://api.bilibili.com",
            "https://passport.bilibili.com",
            "https://m.bilibili.com"
        ).forEach { manager.setCookie(it, cookie) }
        manager.flush()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val payload = Base64.decode(value, Base64.NO_WRAP)
        require(payload.size > 12) { "加密凭据格式无效" }
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    fun profile(context: Context, cookieOverride: String? = null): JSONObject {
        val activeCookie = cookieOverride?.trim()?.takeIf { it.isNotBlank() } ?: cookie(context)
        val c = (URL("https://api.bilibili.com/x/web-interface/nav").openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("User-Agent", BiliHeaders.UA); setRequestProperty("Referer", "https://www.bilibili.com/")
            activeCookie.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
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

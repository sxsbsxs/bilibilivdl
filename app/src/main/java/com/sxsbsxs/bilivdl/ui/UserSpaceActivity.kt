package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UserSpaceActivity : AppCompatActivity() {
    companion object { const val EXTRA_MID = "user_mid" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_space)
        findViewById<android.view.View>(R.id.space_back).setOnClickListener { finish() }
        val mid = intent.getLongExtra(EXTRA_MID, 0L)
        if (mid <= 0L) { finish(); return }
        loadProfile(mid)
    }

    private fun request(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 16000
            setRequestProperty("User-Agent", BiliHeaders.UA)
            setRequestProperty("Referer", "https://www.bilibili.com/")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (body.isBlank()) error("HTTP ${connection.responseCode}")
            JSONObject(body)
        } finally { connection.disconnect() }
    }

    private fun loadProfile(mid: Long) {
        val name = findViewById<TextView>(R.id.space_name)
        val uid = findViewById<TextView>(R.id.space_uid)
        val sign = findViewById<TextView>(R.id.space_sign)
        val stats = findViewById<TextView>(R.id.space_stats)
        val status = findViewById<TextView>(R.id.space_status)
        val avatar = findViewById<ImageView>(R.id.space_avatar)
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    val primary = runCatching { request("https://api.bilibili.com/x/space/acc/info?mid=$mid") }.getOrNull()
                    val primaryData = primary?.takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("data")
                    if (primaryData != null) {
                        val relation = runCatching { request("https://api.bilibili.com/x/relation/stat?vmid=$mid") }
                            .getOrNull()?.optJSONObject("data")
                        JSONObject().put("name", primaryData.optString("name"))
                            .put("face", primaryData.optString("face"))
                            .put("sign", primaryData.optString("sign"))
                            .put("level", primaryData.optInt("level"))
                            .put("follower", relation?.optLong("follower") ?: 0L)
                            .put("following", relation?.optLong("following") ?: 0L)
                    } else {
                        val fallback = request("https://api.bilibili.com/x/web-interface/card?mid=$mid&photo=true")
                        if (fallback.optInt("code", -1) != 0) error(fallback.optString("message", "用户资料加载失败"))
                        val data = fallback.optJSONObject("data") ?: error("用户资料为空")
                        val card = data.optJSONObject("card") ?: error("用户资料为空")
                        JSONObject().put("name", card.optString("name"))
                            .put("face", card.optString("face"))
                            .put("sign", card.optString("sign"))
                            .put("level", card.optJSONObject("level_info")?.optInt("current_level") ?: 0)
                            .put("follower", data.optLong("follower"))
                            .put("following", card.optLong("friend"))
                    }
                }
                name.text = profile.optString("name").ifBlank { "哔哩哔哩用户" }
                uid.text = "UID $mid · LV${profile.optInt("level")}"
                sign.text = profile.optString("sign").ifBlank { "这个用户还没有填写个性签名" }
                stats.text = "关注 ${profile.optLong("following")}    粉丝 ${profile.optLong("follower")}"
                val face = profile.optString("face").let { if (it.startsWith("//")) "https:$it" else it }
                if (face.isNotBlank()) Glide.with(this@UserSpaceActivity)
                    .load(GlideUrl(face, LazyHeaders.Builder().addHeader("Referer", "https://www.bilibili.com/").build()))
                    .placeholder(R.drawable.bg_comment_avatar).error(R.drawable.bg_comment_avatar).circleCrop().into(avatar)
                status.text = "个人资料已加载"
            } catch (error: Exception) {
                status.text = "用户资料加载失败：${error.message ?: "网络异常"}"
            }
        }
    }
}
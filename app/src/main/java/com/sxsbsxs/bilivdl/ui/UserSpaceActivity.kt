package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BilimiaoApiBridge
import com.sxsbsxs.bilivdl.data.BiliVideo
import com.sxsbsxs.bilivdl.data.BiliAccountApi
import com.sxsbsxs.bilivdl.data.BiliSession
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UserSpaceActivity : AppCompatActivity() {
    companion object { const val EXTRA_MID = "user_mid" }
    private lateinit var videosAdapter: VideoAdapter
    private var videosLoading = false
    private var videosHasMore = true
    private var nextVideoAid = 0L
    private var videosPage = 0
    private val loadedVideoKeys = hashSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_space)
        findViewById<View>(R.id.space_back).setOnClickListener { finish() }
        val mid = intent.getLongExtra(EXTRA_MID, 0L)
        if (mid <= 0L) { finish(); return }
        videosAdapter = VideoAdapter { video ->
            startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_BVID, video.bvid)
                putExtra(VideoPlayerActivity.EXTRA_AID, video.aid)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, video.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, video.author)
            })
        }
        findViewById<RecyclerView>(R.id.space_videos).apply {
            layoutManager = LinearLayoutManager(this@UserSpaceActivity)
            adapter = videosAdapter
            isNestedScrollingEnabled = false
        }
        findViewById<ScrollView>(R.id.space_scroll).setOnScrollChangeListener(
            View.OnScrollChangeListener { changedView, _, y, _, _ ->
                val scroll = changedView as ScrollView
                val content = scroll.getChildAt(0) ?: return@OnScrollChangeListener
                if (!videosLoading && videosHasMore && y + scroll.height >= content.measuredHeight - 500) {
                    loadCreatorVideos(mid, reset = false)
                }
            }
        )
        setupOwnAccountShortcuts(mid)
        setupFollowButton(mid)
        loadProfile(mid)
        loadCreatorVideos(mid, reset = true)
    }


    /**
     * The signed-in user's own space gets shortcuts to private account libraries.
     * Other creators' spaces remain focused on their public profile and uploads.
     */
    private fun setupOwnAccountShortcuts(targetMid: Long) {
        val shortcuts = findViewById<View>(R.id.space_own_shortcuts)
        val cookie = BiliSession.cookie(this)
        if (cookie.isBlank()) {
            shortcuts.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val myMid = withContext(Dispatchers.IO) {
                runCatching { BiliSession.profile(this@UserSpaceActivity, cookie).optLong("mid") }
                    .getOrDefault(0L)
            }
            if (myMid != targetMid || myMid <= 0L || isFinishing || isDestroyed) {
                shortcuts.visibility = View.GONE
                return@launch
            }
            shortcuts.visibility = View.VISIBLE
            findViewById<TextView>(R.id.space_own_title).text = "我的内容"
            findViewById<TextView>(R.id.space_video_heading).text = "我的投稿"
            findViewById<TextView>(R.id.space_page_title).text = "我的空间"
            bindOwnShortcut(R.id.space_own_favorites, AccountLibraryActivity.TYPE_FAVORITES, myMid)
            bindOwnShortcut(R.id.space_own_following, AccountLibraryActivity.TYPE_FOLLOWING, myMid)
            bindOwnShortcut(R.id.space_own_bangumi, AccountLibraryActivity.TYPE_BANGUMI, myMid)
            bindOwnShortcut(R.id.space_own_cinema, AccountLibraryActivity.TYPE_CINEMA, myMid)
            bindOwnShortcut(R.id.space_own_history, AccountLibraryActivity.TYPE_HISTORY, myMid)
            bindOwnShortcut(R.id.space_own_watch_later, AccountLibraryActivity.TYPE_WATCH_LATER, myMid)
            bindOwnShortcut(R.id.space_own_liked, AccountLibraryActivity.TYPE_LIKED, myMid)
            bindOwnShortcut(R.id.space_own_messages, AccountLibraryActivity.TYPE_MESSAGES, myMid)
        }
    }

    private fun bindOwnShortcut(viewId: Int, type: String, mid: Long) {
        findViewById<TextView>(viewId).setOnClickListener {
            startActivity(Intent(this, AccountLibraryActivity::class.java).apply {
                putExtra(AccountLibraryActivity.EXTRA_TYPE, type)
                putExtra(AccountLibraryActivity.EXTRA_MID, mid)
            })
        }
    }

    private fun request(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 16000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
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


    private fun setupFollowButton(targetMid: Long) {
        val button = findViewById<MaterialButton>(R.id.space_follow_button)
        val sessionCookie = BiliSession.cookie(this)
        if (sessionCookie.isBlank()) {
            button.text = "登录后关注"
            button.setOnClickListener { startActivity(Intent(this, QrLoginActivity::class.java)) }
            return
        }
        button.isEnabled = false
        lifecycleScope.launch {
            try {
                val myMid = withContext(Dispatchers.IO) {
                    BiliSession.profile(this@UserSpaceActivity, sessionCookie).optLong("mid")
                }
                if (myMid == targetMid) {
                    button.visibility = View.GONE
                    return@launch
                }
                val followed = withContext(Dispatchers.IO) { BiliAccountApi.isFollowing(targetMid) }
                button.text = if (followed) "已关注" else "关注 UP主"
                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    button.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val next = button.text.toString() != "已关注"
                            withContext(Dispatchers.IO) { BiliAccountApi.follow(targetMid, next) }
                            button.text = if (next) "已关注" else "关注 UP主"
                            findViewById<TextView>(R.id.space_status).text =
                                if (next) "已关注这个 UP 主" else "已取消关注"
                        } catch (error: Exception) {
                            findViewById<TextView>(R.id.space_status).text = error.message ?: "关注操作失败"
                        } finally {
                            button.isEnabled = true
                        }
                    }
                }
            } catch (error: Exception) {
                button.visibility = View.VISIBLE
                button.text = "关注状态不可用"
                findViewById<TextView>(R.id.space_status).text = error.message ?: "无法获取关注状态"
            } finally {
                button.isEnabled = true
            }
        }
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
                    val appSpace = runCatching { BilimiaoApiBridge.userSpace(mid) }.getOrNull()
                    val appCard = appSpace?.optJSONObject("card")
                    if (appCard != null) {
                        JSONObject().put("name", appCard.optString("name"))
                            .put("face", appCard.optString("face"))
                            .put("sign", appCard.optString("sign"))
                            .put("level", appCard.optJSONObject("level_info")?.optInt("current_level") ?: 0)
                            .put("follower", appCard.optLong("fans"))
                            .put("following", appCard.optLong("friend"))
                    } else {
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

    private fun loadCreatorVideos(mid: Long, reset: Boolean) {
        if (videosLoading || (!reset && !videosHasMore)) return
        if (reset) {
            videosHasMore = true
            nextVideoAid = 0L
            videosPage = 0
            loadedVideoKeys.clear()
            videosAdapter.submitList(emptyList())
        }
        videosLoading = true
        val status = findViewById<TextView>(R.id.space_video_status)
        status.visibility = View.VISIBLE
        status.text = if (reset) "正在加载投稿…" else "正在加载更多…"
        val cursor = nextVideoAid
        val page = videosPage + 1
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        BilimiaoApiBridge.userVideos(mid, cursorAid = cursor, pageSize = 30)
                    }.getOrElse { upstreamError ->
                        val root = request("https://api.bilibili.com/x/space/wbi/arc/search?mid=$mid&ps=30&pn=$page&order=pubdate")
                        if (root.optInt("code", -1) != 0) throw upstreamError
                        val vlist = root.optJSONObject("data")?.optJSONObject("list")?.optJSONArray("vlist")
                        (0 until (vlist?.length() ?: 0)).mapNotNull { index ->
                            val item = vlist?.optJSONObject(index) ?: return@mapNotNull null
                            val aid = item.optLong("aid")
                            if (aid <= 0L) return@mapNotNull null
                            BiliVideo(
                                title = item.optString("title"), cover = item.optString("pic"),
                                author = item.optString("author"), play = item.optString("play"),
                                duration = item.optString("length"), bvid = item.optString("bvid"),
                                aid = aid, description = item.optString("description")
                            )
                        }
                    }
                }
                val fresh = result.filter { video ->
                    val key = video.bvid.ifBlank { video.aid.toString() }
                    key.isNotBlank() && loadedVideoKeys.add(key)
                }
                videosAdapter.appendList(fresh)
                if (fresh.isNotEmpty()) {
                    nextVideoAid = fresh.last().aid
                    videosPage = page
                }
                videosHasMore = result.size >= 30 && fresh.isNotEmpty()
                status.text = when {
                    loadedVideoKeys.isEmpty() -> "这个 UP 主还没有公开投稿"
                    videosHasMore -> "已加载 ${loadedVideoKeys.size} 个视频 · 继续下滑加载"
                    else -> "已加载全部 ${loadedVideoKeys.size} 个视频"
                }
            } catch (error: Exception) {
                status.text = "投稿加载失败：${error.message ?: "网络异常"}"
            } finally {
                videosLoading = false
            }
        }
    }
}

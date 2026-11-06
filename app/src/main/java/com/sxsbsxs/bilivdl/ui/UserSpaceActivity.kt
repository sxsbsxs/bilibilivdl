package com.sxsbsxs.bilivdl.ui

import android.app.AlertDialog
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
import com.sxsbsxs.bilivdl.data.BiliDynamicApi
import com.sxsbsxs.bilivdl.data.BiliDynamicItem
import com.sxsbsxs.bilivdl.data.BiliAccountApi
import com.sxsbsxs.bilivdl.data.BiliSession
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UserSpaceActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MID = "user_mid"
        const val EXTRA_GUEST_MODE = "guest_mode"
    }
    private lateinit var videosAdapter: VideoAdapter
    private lateinit var homeVideosAdapter: VideoAdapter
    private lateinit var dynamicsAdapter: DynamicAdapter
    private val creatorVideos = mutableListOf<BiliVideo>()
    private val dynamicLikeJobs = mutableSetOf<String>()
    private var currentSpaceTab = 0
    private var dynamicHistoryOffset = ""
    private var dynamicPage = 1
    private var dynamicHasMore = true
    private var dynamicLoading = false
    private val loadedDynamicIds = hashSetOf<String>()
    private var videosLoading = false
    private var videosHasMore = true
    private var nextVideoAid = 0L
    private var videosPage = 0
    private var videoSortOrder = "pubdate"
    private var refreshVideosAfterLoad = false
    private val loadedVideoKeys = hashSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_space)
        findViewById<View>(R.id.space_back).setOnClickListener { finish() }
        if (intent.getBooleanExtra(EXTRA_GUEST_MODE, false)) {
            showGuestSpace()
            return
        }
        val mid = intent.getLongExtra(EXTRA_MID, 0L)
        if (mid <= 0L) { finish(); return }
        videosAdapter = VideoAdapter(::openCreatorVideo)
        homeVideosAdapter = VideoAdapter(::openCreatorVideo)
        dynamicsAdapter = DynamicAdapter(
            onOpen = ::openSpaceDynamic,
            onUser = ::openSpaceDynamicUser,
            onLike = ::toggleSpaceDynamicLike,
            onReply = ::openSpaceDynamicReplies,
            onShare = ::shareSpaceDynamic
        )
        findViewById<RecyclerView>(R.id.space_videos).apply {
            layoutManager = LinearLayoutManager(this@UserSpaceActivity)
            adapter = videosAdapter
            isNestedScrollingEnabled = false
        }
        findViewById<RecyclerView>(R.id.space_home_videos).apply {
            layoutManager = LinearLayoutManager(this@UserSpaceActivity)
            adapter = homeVideosAdapter
            isNestedScrollingEnabled = false
        }
        findViewById<RecyclerView>(R.id.space_dynamics).apply {
            layoutManager = LinearLayoutManager(this@UserSpaceActivity)
            adapter = dynamicsAdapter
            isNestedScrollingEnabled = false
        }
        val spaceTabs = findViewById<TabLayout>(R.id.space_tabs)
        listOf("主页", "动态", "投稿").forEach { spaceTabs.addTab(spaceTabs.newTab().setText(it)) }
        spaceTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSpaceTab(tab.position, mid)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                when (tab.position) {
                    1 -> loadSpaceDynamics(mid, reset = true)
                    2 -> findViewById<ScrollView>(R.id.space_scroll).smoothScrollTo(0, 0)
                }
            }
        })
        showSpaceTab(0, mid)
        findViewById<TextView>(R.id.space_home_all).setOnClickListener {
            spaceTabs.getTabAt(2)?.select()
        }
        findViewById<TextView>(R.id.space_video_tab).apply {
            text = videoSortLabel()
            setOnClickListener { showVideoSortMenu(mid) }
        }
        findViewById<ScrollView>(R.id.space_scroll).setOnScrollChangeListener(
            View.OnScrollChangeListener { changedView, _, y, _, _ ->
                val scroll = changedView as ScrollView
                val content = scroll.getChildAt(0) ?: return@OnScrollChangeListener
                if (y + scroll.height < content.measuredHeight - 500) return@OnScrollChangeListener
                when (currentSpaceTab) {
                    1 -> if (!dynamicLoading && dynamicHasMore) loadSpaceDynamics(mid, reset = false)
                    2 -> if (!videosLoading && videosHasMore) loadCreatorVideos(mid, reset = false)
                }
            }
        )
        setupOwnAccountShortcuts(mid)
        setupFollowButton(mid)
        loadProfile(mid)
        loadCreatorVideos(mid, reset = true)
    }


    /** Displays the guest personal-space shell without blocking browsing behind login. */
    private fun showGuestSpace() {
        findViewById<TextView>(R.id.space_page_title).text = "个人空间"
        findViewById<TextView>(R.id.space_name).text = "未登录"
        findViewById<TextView>(R.id.space_uid).text = "登录 B 站账号后查看 UID 和等级"
        findViewById<TextView>(R.id.space_sign).text = "登录后可同步个人资料、关注、收藏、追番和历史记录。游客仍可搜索、浏览和播放公开内容。"
        findViewById<TextView>(R.id.space_stats).text = "粉丝 —    关注 —    获赞 —"
        findViewById<View>(R.id.space_verification_row).visibility = View.GONE
        findViewById<TextView>(R.id.space_location).visibility = View.GONE
        findViewById<ImageView>(R.id.space_banner).setImageResource(R.drawable.bg_home_hero)
        findViewById<ImageView>(R.id.space_avatar).apply {
            setImageResource(R.drawable.bili_akari_img)
            setOnClickListener { startActivity(Intent(this@UserSpaceActivity, QrLoginActivity::class.java)) }
        }
        findViewById<View>(R.id.space_tabs).visibility = View.GONE
        findViewById<View>(R.id.space_home_panel).visibility = View.GONE
        findViewById<View>(R.id.space_dynamics_panel).visibility = View.GONE
        findViewById<View>(R.id.space_posts_panel).visibility = View.GONE
        findViewById<TextView>(R.id.space_status).text = "游客模式"
        findViewById<MaterialButton>(R.id.space_follow_button).apply {
            text = "B站 APP 扫码登录"
            visibility = View.VISIBLE
            setOnClickListener { startActivity(Intent(this@UserSpaceActivity, QrLoginActivity::class.java)) }
        }
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
                        val verification = appCard.optJSONObject("official_verify") ?: JSONObject()
                        val levelInfo = appCard.optJSONObject("level_info") ?: JSONObject()
                        val likes = appCard.optJSONObject("likes") ?: JSONObject()
                        val tags = appCard.optJSONArray("space_tag")
                        val location = (0 until (tags?.length() ?: 0))
                            .mapNotNull { tags?.optJSONObject(it) }
                            .firstOrNull { it.optString("type") == "location" }
                            ?.optString("title").orEmpty()
                            .ifBlank { appCard.optString("place") }
                        JSONObject().put("name", appCard.optString("name"))
                            .put("face", appCard.optString("face"))
                            .put("sign", appCard.optString("sign"))
                            .put("level", levelInfo.optInt("current_level"))
                            .put("follower", appCard.optLong("fans"))
                            .put("following", appCard.optLong("attention", appCard.optLong("friend")))
                            .put("likes", likes.optLong("like_num"))
                            .put("location", location)
                            .put("cover", appSpace.optJSONObject("images")?.optString("imgUrl").orEmpty())
                            .put("verify_title", verification.optString("title").ifBlank { verification.optString("desc") })
                            .put("verify_icon", verification.optString("icon"))
                    } else {
                    val primary = runCatching { request("https://api.bilibili.com/x/space/acc/info?mid=$mid") }.getOrNull()
                    val primaryData = primary?.takeIf { it.optInt("code", -1) == 0 }?.optJSONObject("data")
                    if (primaryData != null) {
                        val relation = runCatching { request("https://api.bilibili.com/x/relation/stat?vmid=$mid") }
                            .getOrNull()?.optJSONObject("data")
                        val verification = primaryData.optJSONObject("official_verify") ?: JSONObject()
                        JSONObject().put("name", primaryData.optString("name"))
                            .put("face", primaryData.optString("face"))
                            .put("sign", primaryData.optString("sign"))
                            .put("level", primaryData.optInt("level"))
                            .put("follower", relation?.optLong("follower") ?: 0L)
                            .put("following", relation?.optLong("following") ?: 0L)
                            .put("likes", primaryData.optLong("likes"))
                            .put("location", primaryData.optString("place"))
                            .put("cover", primaryData.optString("top_photo").ifBlank { primaryData.optString("top_photo_v2") })
                            .put("verify_title", verification.optString("desc"))
                            .put("verify_icon", verification.optString("icon"))
                    } else {
                        val fallback = request("https://api.bilibili.com/x/web-interface/card?mid=$mid&photo=true")
                        if (fallback.optInt("code", -1) != 0) error(fallback.optString("message", "用户资料加载失败"))
                        val data = fallback.optJSONObject("data") ?: error("用户资料为空")
                        val card = data.optJSONObject("card") ?: error("用户资料为空")
                        val verification = card.optJSONObject("official_verify") ?: JSONObject()
                        JSONObject().put("name", card.optString("name"))
                            .put("face", card.optString("face"))
                            .put("sign", card.optString("sign"))
                            .put("level", card.optJSONObject("level_info")?.optInt("current_level") ?: 0)
                            .put("follower", data.optLong("follower"))
                            .put("following", card.optLong("friend"))
                            .put("likes", data.optLong("like_num", card.optLong("likes")))
                            .put("location", card.optString("place"))
                            .put("cover", card.optString("top_photo"))
                            .put("verify_title", verification.optString("desc"))
                            .put("verify_icon", verification.optString("icon"))
                    }
                    }
                }
                name.text = profile.optString("name").ifBlank { "哔哩哔哩用户" }
                uid.text = "UID $mid · LV${profile.optInt("level")}"
                sign.text = profile.optString("sign").ifBlank { "这个用户还没有填写个性签名" }
                stats.text = "粉丝 ${formatProfileCount(profile.optLong("follower"))}    关注 ${formatProfileCount(profile.optLong("following"))}    获赞 ${formatProfileCount(profile.optLong("likes"))}"

                val verificationTitle = profile.optString("verify_title").trim()
                val verificationRow = findViewById<View>(R.id.space_verification_row)
                val verificationText = findViewById<TextView>(R.id.space_verification)
                val verificationIcon = findViewById<ImageView>(R.id.space_verification_icon)
                if (verificationTitle.isNotBlank()) {
                    verificationRow.visibility = View.VISIBLE
                    verificationText.text = verificationTitle
                    val iconUrl = profile.optString("verify_icon").let {
                        if (it.startsWith("//")) "https:$it" else it
                    }
                    if (iconUrl.isNotBlank()) {
                        verificationIcon.visibility = View.VISIBLE
                        Glide.with(this@UserSpaceActivity).load(iconUrl).into(verificationIcon)
                    } else {
                        verificationIcon.visibility = View.GONE
                    }
                } else {
                    verificationRow.visibility = View.GONE
                }

                val location = profile.optString("location").trim()
                findViewById<TextView>(R.id.space_location).apply {
                    text = location
                    visibility = if (location.isBlank()) View.GONE else View.VISIBLE
                }

                val face = profile.optString("face").let { if (it.startsWith("//")) "https:$it" else it }
                if (face.isNotBlank()) Glide.with(this@UserSpaceActivity)
                    .load(GlideUrl(face, LazyHeaders.Builder().addHeader("Referer", "https://www.bilibili.com/").build()))
                    .placeholder(R.drawable.bili_akari_img).error(R.drawable.bili_akari_img).circleCrop().into(avatar)

                val cover = profile.optString("cover").let { if (it.startsWith("//")) "https:$it" else it }
                if (cover.isNotBlank()) {
                    Glide.with(this@UserSpaceActivity)
                        .load(GlideUrl(cover, LazyHeaders.Builder().addHeader("Referer", "https://www.bilibili.com/").build()))
                        .centerCrop()
                        .placeholder(R.drawable.bg_home_hero)
                        .error(R.drawable.bg_home_hero)
                        .into(findViewById<ImageView>(R.id.space_banner))
                } else {
                    findViewById<ImageView>(R.id.space_banner).setImageResource(R.drawable.bg_home_hero)
                }
                status.text = "个人资料已加载"
            } catch (error: Exception) {
                status.text = "用户资料加载失败：${error.message ?: "网络异常"}"
            }
        }
    }

    private fun formatProfileCount(value: Long): String = when {
        value >= 100_000_000L -> String.format(java.util.Locale.getDefault(), "%.1f亿", value / 100_000_000.0)
        value >= 10_000L -> String.format(java.util.Locale.getDefault(), "%.1f万", value / 10_000.0)
        else -> value.toString()
    }

    private fun openCreatorVideo(video: BiliVideo) {
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_BVID, video.bvid)
            putExtra(VideoPlayerActivity.EXTRA_AID, video.aid)
            putExtra(VideoPlayerActivity.EXTRA_TITLE, video.title)
            putExtra(VideoPlayerActivity.EXTRA_COVER, video.cover)
            putExtra(VideoPlayerActivity.EXTRA_AUTHOR, video.author)
        })
    }

    private fun showSpaceTab(index: Int, mid: Long) {
        currentSpaceTab = index
        findViewById<View>(R.id.space_home_panel).visibility = if (index == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.space_dynamics_panel).visibility = if (index == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.space_posts_panel).visibility = if (index == 2) View.VISIBLE else View.GONE
        if (index == 1 && dynamicsAdapter.count() == 0 && !dynamicLoading) {
            loadSpaceDynamics(mid, reset = true)
        }
    }

    private fun loadSpaceDynamics(mid: Long, reset: Boolean) {
        if (dynamicLoading || (!reset && !dynamicHasMore)) return
        if (reset) {
            dynamicHistoryOffset = ""
            dynamicPage = 1
            dynamicHasMore = true
            loadedDynamicIds.clear()
            dynamicsAdapter.submit(emptyList())
        }
        dynamicLoading = true
        val requestOffset = dynamicHistoryOffset
        val requestPage = dynamicPage
        val status = findViewById<TextView>(R.id.space_dynamics_status)
        status.text = if (reset) "正在加载 UP 主动态…" else "正在加载更多动态…"
        status.setOnClickListener { loadSpaceDynamics(mid, reset = true) }
        status.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    BilimiaoApiBridge.userDynamics(mid, requestOffset, requestPage)
                }
                val fresh = page.items.filter { it.id.isNotBlank() && loadedDynamicIds.add(it.id) }
                dynamicsAdapter.submit(fresh, append = !reset)
                dynamicHistoryOffset = page.offset
                dynamicPage = requestPage + 1
                dynamicHasMore = page.hasMore && fresh.isNotEmpty()
                status.text = when {
                    loadedDynamicIds.isEmpty() -> "这个 UP 主还没有公开动态"
                    dynamicHasMore -> "已加载 ${loadedDynamicIds.size} 条动态 · 继续下滑"
                    else -> "已加载全部 ${loadedDynamicIds.size} 条动态"
                }
                status.setOnClickListener(null)
            } catch (error: Exception) {
                status.text = (error.message ?: "动态加载失败") + " · 点击重试"
                status.setOnClickListener { loadSpaceDynamics(mid, reset = true) }
                dynamicHasMore = false
            } finally {
                dynamicLoading = false
            }
        }
    }

    private fun openSpaceDynamic(item: BiliDynamicItem) {
        when {
            item.episodeId > 0L -> startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_EP_ID, item.episodeId)
                putExtra(VideoPlayerActivity.EXTRA_SEASON_ID, item.seasonId)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, item.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, item.ownerName)
            })
            item.aid > 0L || item.bvid.isNotBlank() -> startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_AID, item.aid)
                putExtra(VideoPlayerActivity.EXTRA_BVID, item.bvid)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, item.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, item.ownerName)
            })
            item.url.isNotBlank() -> openSpaceUrl(item.url)
            else -> Unit
        }
    }

    private fun openSpaceDynamicUser(mid: Long) {
        if (mid > 0L) startActivity(Intent(this, UserSpaceActivity::class.java)
            .putExtra(EXTRA_MID, mid))
    }

    private fun toggleSpaceDynamicLike(item: BiliDynamicItem) {
        if (dynamicLikeJobs.contains(item.id)) return
        val activeCookie = BiliSession.cookie(this)
        if (activeCookie.isBlank()) {
            findViewById<TextView>(R.id.space_dynamics_status).apply {
                text = "动态点赞需要先使用 B站 APP 扫码登录"
                setOnClickListener { startActivity(Intent(this@UserSpaceActivity, QrLoginActivity::class.java)) }
            }
            return
        }
        dynamicLikeJobs += item.id
        val next = !item.liked
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { BiliDynamicApi.toggleLike(activeCookie, item.id, next) }
                dynamicsAdapter.updateLike(item.id, next)
            } catch (error: Exception) {
                findViewById<TextView>(R.id.space_dynamics_status).text =
                    error.message ?: "动态点赞失败"
            } finally {
                dynamicLikeJobs.remove(item.id)
            }
        }
    }

    private fun openSpaceDynamicReplies(item: BiliDynamicItem) {
        startActivity(Intent(this, DynamicCommentsActivity::class.java).apply {
            putExtra(DynamicCommentsActivity.EXTRA_DYNAMIC_ID, item.id)
            putExtra(DynamicCommentsActivity.EXTRA_TITLE, item.title)
            putExtra(DynamicCommentsActivity.EXTRA_TEXT, item.text)
            putExtra(DynamicCommentsActivity.EXTRA_AUTHOR, item.ownerName)
            putExtra(DynamicCommentsActivity.EXTRA_OWNER_MID, item.ownerMid)
            putExtra(DynamicCommentsActivity.EXTRA_FACE, item.ownerFace)
            putExtra(DynamicCommentsActivity.EXTRA_COVER, item.cover)
        })
    }

    private fun shareSpaceDynamic(item: BiliDynamicItem) {
        val url = item.url.ifBlank { "https://t.bilibili.com/${item.id}" }
        val content = buildString {
            if (item.text.isNotBlank()) append(item.text.take(160)).append("\n")
            if (item.title.isNotBlank()) append(item.title).append("\n")
            append(url)
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }, "分享动态"))
    }

    private fun openSpaceUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure {
            findViewById<TextView>(R.id.space_dynamics_status).text = "无法打开动态链接"
        }
    }

    private fun videoSortLabel(): String =
        if (videoSortOrder == "pubdate") "最新发布 ▾" else "最多播放 ▾"

    private fun showVideoSortMenu(mid: Long) {
        val selected = if (videoSortOrder == "pubdate") 0 else 1
        AlertDialog.Builder(this)
            .setTitle("投稿排序")
            .setSingleChoiceItems(arrayOf("最新发布", "最多播放"), selected) { dialog, which ->
                val nextOrder = if (which == 0) "pubdate" else "click"
                dialog.dismiss()
                if (nextOrder != videoSortOrder) {
                    videoSortOrder = nextOrder
                    findViewById<TextView>(R.id.space_video_tab).text = videoSortLabel()
                    loadCreatorVideos(mid, reset = true)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadCreatorVideos(mid: Long, reset: Boolean) {
        if (videosLoading) {
            if (reset) refreshVideosAfterLoad = true
            return
        }
        if (!reset && !videosHasMore) return
        if (reset) {
            videosHasMore = true
            nextVideoAid = 0L
            videosPage = 0
            loadedVideoKeys.clear()
            videosAdapter.submitList(emptyList())
            creatorVideos.clear()
            homeVideosAdapter.submitList(emptyList())
            findViewById<TextView>(R.id.space_home_status).text = "正在加载最近投稿…"
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
                        BilimiaoApiBridge.userVideos(
                            mid, cursorAid = cursor, pageSize = 30, order = videoSortOrder
                        )
                    }.getOrElse { upstreamError ->
                        val root = request("https://api.bilibili.com/x/space/wbi/arc/search?mid=$mid&ps=30&pn=$page&order=$videoSortOrder")
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
                creatorVideos.addAll(fresh)
                homeVideosAdapter.submitList(creatorVideos.take(3))
                findViewById<TextView>(R.id.space_home_status).text = when {
                    creatorVideos.isEmpty() -> "这个 UP 主还没有公开投稿"
                    else -> "最近更新 ${creatorVideos.size.coerceAtMost(3)} 个视频"
                }
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
                if (refreshVideosAfterLoad && !isFinishing && !isDestroyed) {
                    refreshVideosAfterLoad = false
                    loadCreatorVideos(mid, reset = true)
                }
            }
        }
    }
}

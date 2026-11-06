package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.AccountCenterItem
import com.sxsbsxs.bilivdl.data.BiliAccountApi
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountLibraryActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TYPE = "account_library_type"
        const val EXTRA_MID = "account_library_mid"
        const val EXTRA_MEDIA_ID = "account_library_media_id"
        const val TYPE_HISTORY = "history"
        const val TYPE_WATCH_LATER = "watch_later"
        const val TYPE_FAVORITES = "favorites"
        const val TYPE_FAVORITE_ITEMS = "favorite_items"
        const val TYPE_FOLLOWING = "following"
        const val TYPE_LIKED = "liked"
        const val TYPE_MESSAGES = "messages"
        const val TYPE_BANGUMI = "bangumi"
        const val TYPE_CINEMA = "cinema"
    }

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var loadMoreButton: MaterialButton
    private var type = TYPE_HISTORY
    private var accountMid = 0L
    private var mediaId = ""
    private var watchLaterCursor = ""
    private var selectedBangumiStatus = 0
    private lateinit var bangumiFilterRow: LinearLayout
    private var page = 1
    private var loading = false
    private var hasMore = true
    private val seenKeys = hashSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = intent.getStringExtra(EXTRA_TYPE).orEmpty().ifBlank { TYPE_HISTORY }
        accountMid = intent.getLongExtra(EXTRA_MID, 0L)
        mediaId = intent.getStringExtra(EXTRA_MEDIA_ID).orEmpty()
        if (BiliSession.cookie(this).isBlank()) {
            startActivity(Intent(this, QrLoginActivity::class.java))
            finish()
            return
        }
        buildPage()
        if (accountMid <= 0L) {
            lifecycleScope.launch {
                runCatching {
                    accountMid = withContext(Dispatchers.IO) { BiliSession.profile(this@AccountLibraryActivity).optLong("mid") }
                }
                loadPage(reset = true)
            }
        } else loadPage(reset = true)
    }

    private fun buildPage() {
        val background = Color.rgb(247, 248, 250)
        val ink = Color.rgb(32, 36, 43)
        val muted = Color.rgb(130, 137, 148)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))
        titleView = TextView(this).apply {
            text = titleForType()
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        val refresh = TextView(this).apply {
            text = "刷新"
            textSize = 13f
            setTextColor(Color.rgb(0, 161, 214))
            gravity = Gravity.CENTER
            setOnClickListener { loadPage(reset = true) }
        }
        toolbar.addView(refresh, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar)

        statusView = TextView(this).apply {
            text = "正在加载…"
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(18), dp(5), dp(18), dp(8))
        }
        root.addView(statusView)
        bangumiFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(8))
            visibility = if (type == TYPE_BANGUMI || type == TYPE_CINEMA) View.VISIBLE else View.GONE
        }
        root.addView(bangumiFilterRow)
        renderBangumiFilters()
        scrollView = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(14), dp(4), dp(14), dp(24))
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollView.addView(listContainer)
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
        loadMoreButton = MaterialButton(this).apply {
            text = "加载更多"
            isAllCaps = false
            setOnClickListener { loadPage(reset = false) }
        }
        root.addView(loadMoreButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            setMargins(dp(16), dp(4), dp(16), dp(12))
        })
        loadMoreButton.visibility = View.GONE
        scrollView.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            val child = scrollView.getChildAt(0) ?: return@setOnScrollChangeListener
            if (hasMore && !loading && y + scrollView.height >= child.measuredHeight - dp(500)) {
                loadPage(reset = false)
            }
        }
        setContentView(root)
    }


    private fun renderBangumiFilters() {
        if (!::bangumiFilterRow.isInitialized) return
        bangumiFilterRow.removeAllViews()
        val labels = listOf("全部", "想看", "在看", "看过")
        labels.forEachIndexed { index, label ->
            val selected = selectedBangumiStatus == index
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setTextColor(if (selected) Color.WHITE else Color.rgb(75, 82, 93))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (selected) Color.rgb(0, 161, 214) else Color.rgb(235, 238, 242))
                }
                setOnClickListener {
                    if (selectedBangumiStatus != index) {
                        selectedBangumiStatus = index
                        renderBangumiFilters()
                        loadPage(true)
                    }
                }
            }
            bangumiFilterRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun titleForType(): String = when (type) {
        TYPE_HISTORY -> "历史记录"
        TYPE_WATCH_LATER -> "稍后再看"
        TYPE_FAVORITES -> "我的收藏夹"
        TYPE_FAVORITE_ITEMS -> "收藏内容"
        TYPE_FOLLOWING -> "我的关注"
        TYPE_LIKED -> "我赞过的视频"
        TYPE_MESSAGES -> "消息概览"
        TYPE_BANGUMI -> "我的追番"
        TYPE_CINEMA -> "我的追剧"
        else -> "账号内容"
    }

    private fun loadPage(reset: Boolean) {
        if (loading || (!reset && !hasMore)) return
        if (reset) {
            page = 1
            hasMore = true
            seenKeys.clear()
            watchLaterCursor = ""
            listContainer.removeAllViews()
        }
        loading = true
        loadMoreButton.isEnabled = false
        loadMoreButton.text = "正在加载…"
        statusView.text = if (reset) "正在加载…" else "正在加载更多…"
        lifecycleScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    when (type) {
                        TYPE_HISTORY -> BiliAccountApi.history(page)
                        TYPE_WATCH_LATER -> BiliAccountApi.watchLater(watchLaterCursor)
                        TYPE_FAVORITES -> BiliAccountApi.favoriteFolders(accountMid, page)
                        TYPE_FAVORITE_ITEMS -> BiliAccountApi.favoriteItems(mediaId, page)
                        TYPE_FOLLOWING -> BiliAccountApi.followings(accountMid, page)
                        TYPE_LIKED -> BiliAccountApi.likedVideos(accountMid, page)
                        TYPE_MESSAGES -> null
                        TYPE_BANGUMI -> BiliAccountApi.bangumi("bangumi", selectedBangumiStatus, page)
                        TYPE_CINEMA -> BiliAccountApi.bangumi("cinema", selectedBangumiStatus, page)
                        else -> error("未知的账号页面")
                    }
                }
                if (type == TYPE_MESSAGES) {
                    val messages = withContext(Dispatchers.IO) { BiliAccountApi.unreadMessages() }
                    messages.forEach(::addRow)
                    hasMore = false
                    statusView.text = "以下为账号当前未读消息数量"
                } else {
                    val entries = payload?.items.orEmpty()
                    entries.forEach { item ->
                        val key = when (item.kind) {
                            "video" -> item.bvid.ifBlank { item.aid.toString() }
                            "folder" -> item.mediaId
                            "user" -> item.mid.toString()
                            "bangumi" -> item.mediaId
                            else -> item.title
                        }
                        if (key.isNotBlank() && seenKeys.add(key)) addRow(item)
                    }
                    hasMore = payload?.hasMore == true
                    if (type == TYPE_WATCH_LATER) watchLaterCursor = payload?.nextCursor.orEmpty()
                    statusView.text = when {
                        seenKeys.isEmpty() -> emptyText()
                        hasMore -> "已加载 " + seenKeys.size + " 项"
                        else -> "已加载全部 " + seenKeys.size + " 项"
                    }
                    page++
                }
                if (type == TYPE_MESSAGES) {
                    statusView.text = "消息未读概览"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statusView.text = error.message ?: "加载失败，请稍后刷新"
            } finally {
                loading = false
                loadMoreButton.isEnabled = true
                loadMoreButton.visibility = if (hasMore) View.VISIBLE else View.GONE
                loadMoreButton.text = "加载更多"
            }
        }
    }

    private fun emptyText(): String = when (type) {
        TYPE_HISTORY -> "暂时没有历史记录"
        TYPE_WATCH_LATER -> "稍后再看列表为空"
        TYPE_FAVORITES -> "还没有可显示的收藏夹"
        TYPE_FAVORITE_ITEMS -> "这个收藏夹还没有视频"
        TYPE_FOLLOWING -> "还没有关注的 UP 主"
        TYPE_LIKED -> "还没有点赞过的视频"
        TYPE_BANGUMI -> "还没有追番记录"
        TYPE_CINEMA -> "还没有追剧记录"
        else -> "暂无内容"
    }

    private fun addRow(item: AccountCenterItem) {
        val ink = Color.rgb(36, 40, 47)
        val muted = Color.rgb(132, 139, 150)
        val card = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = dp(1)
            strokeColor = Color.rgb(237, 239, 242)
            useCompatPadding = false
            isClickable = true
            isFocusable = true
            setOnClickListener { openItem(item) }
            setOnLongClickListener { handleLongPress(item); true }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(10), dp(12), dp(10))
        }
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(238, 241, 245))
            clipToOutline = true
            contentDescription = item.title
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(104), dp(70)))
        if (item.cover.isNotBlank()) {
            val url = GlideUrl(item.cover, LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/").build())
            Glide.with(this).load(url).placeholder(R.drawable.bg_comment_avatar)
                .error(R.drawable.bg_comment_avatar).centerCrop().into(avatar)
        } else {
            avatar.setImageResource(R.drawable.bg_comment_avatar)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val title = TextView(this).apply {
            text = item.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            maxLines = 2
        }
        val subtitle = TextView(this).apply {
            text = item.subtitle
            textSize = 11f
            setTextColor(muted)
            maxLines = 2
            setPadding(0, dp(5), 0, 0)
        }
        textColumn.addView(title)
        textColumn.addView(subtitle)
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        val params = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(9)) }
        listContainer.addView(card, params)
    }

    private fun openItem(item: AccountCenterItem) {
        when (item.kind) {
            "video" -> startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_BVID, item.bvid)
                putExtra(VideoPlayerActivity.EXTRA_AID, item.aid)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, item.title)
                putExtra(VideoPlayerActivity.EXTRA_COVER, item.cover)
                putExtra(VideoPlayerActivity.EXTRA_AUTHOR, item.subtitle.substringBefore(" · "))
            })
            "folder" -> startActivity(Intent(this, AccountLibraryActivity::class.java).apply {
                putExtra(EXTRA_TYPE, TYPE_FAVORITE_ITEMS)
                putExtra(EXTRA_MID, accountMid)
                putExtra(EXTRA_MEDIA_ID, item.mediaId)
            })
            "user" -> startActivity(Intent(this, UserSpaceActivity::class.java)
                .putExtra(UserSpaceActivity.EXTRA_MID, item.mid))
            "bangumi" -> startActivity(Intent(this, BangumiSeasonActivity::class.java).apply {
                putExtra(BangumiSeasonActivity.EXTRA_SEASON_ID, item.mediaId.toLongOrNull() ?: 0L)
                putExtra(BangumiSeasonActivity.EXTRA_TITLE, item.title)
                putExtra(BangumiSeasonActivity.EXTRA_COVER, item.cover)
            })
        }
    }

    private fun handleLongPress(item: AccountCenterItem) {
        when {
            type == TYPE_WATCH_LATER && item.kind == "video" -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(arrayOf("从稍后再看移除")) { _, _ ->
                    lifecycleScope.launch {
                        runCatching { withContext(Dispatchers.IO) { BiliAccountApi.removeFromWatchLater(item.aid) } }
                            .onSuccess { statusView.text = "已移除"; loadPage(true) }
                            .onFailure { statusView.text = it.message ?: "移除失败" }
                    }
                }.show()
            (type == TYPE_BANGUMI || type == TYPE_CINEMA) && item.kind == "bangumi" ->
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setItems(arrayOf("取消追番/追剧", "标记为想看", "标记为在看", "标记为看过")) { _, which ->
                        lifecycleScope.launch {
                            val targetStatus = when (which) { 1 -> 1; 2 -> 2; 3 -> 3; else -> 0 }
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    BiliAccountApi.changeBangumiStatus(item.mediaId, targetStatus)
                                }
                            }.onSuccess {
                                statusView.text = if (targetStatus == 0) "已取消追番/追剧" else "已更新观看状态"
                                loadPage(true)
                            }.onFailure { statusView.text = it.message ?: "更新追番状态失败" }
                        }
                    }.show()
            type == TYPE_FOLLOWING && item.kind == "user" -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setItems(arrayOf("取消关注")) { _, _ ->
                    lifecycleScope.launch {
                        runCatching { withContext(Dispatchers.IO) { BiliAccountApi.follow(item.mid, false) } }
                            .onSuccess { statusView.text = "已取消关注"; loadPage(true) }
                            .onFailure { statusView.text = it.message ?: "操作失败" }
                    }
                }.show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

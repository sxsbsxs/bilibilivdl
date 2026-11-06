package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
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
import com.sxsbsxs.bilivdl.data.BangumiApiBridge
import com.sxsbsxs.bilivdl.data.BangumiEpisode
import com.sxsbsxs.bilivdl.data.BiliAccountApi
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BangumiSeasonActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SEASON_ID = "account_bangumi_season_id"
        const val EXTRA_TITLE = "account_bangumi_title"
        const val EXTRA_COVER = "account_bangumi_cover"
    }

    private var seasonId = 0L
    private var cover = ""
    private lateinit var titleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var statusView: TextView
    private lateinit var episodeList: LinearLayout
    private lateinit var followButton: MaterialButton
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seasonId = intent.getLongExtra(EXTRA_SEASON_ID, 0L)
        cover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        if (seasonId <= 0L) {
            finish()
            return
        }
        buildPage()
        loadSeason()
    }

    private fun buildPage() {
        val background = Color.rgb(247, 248, 250)
        val ink = Color.rgb(35, 39, 47)
        val muted = Color.rgb(128, 136, 147)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(38), dp(48)))
        titleView = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "番剧详情" }
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            maxLines = 1
        }
        toolbar.addView(titleView, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(toolbar)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            setPadding(dp(14), dp(6), dp(14), dp(24))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = MaterialCardView(this).apply {
            radius = dp(15).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = dp(1)
            strokeColor = Color.rgb(237, 239, 242)
        }
        val headerContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "番剧封面"
        }
        headerContent.addView(coverView, LinearLayout.LayoutParams(dp(112), dp(150)))
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        val name = TextView(this).apply {
            text = titleView.text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            maxLines = 3
        }
        descriptionView = TextView(this).apply {
            textSize = 12f
            setTextColor(muted)
            maxLines = 5
            setPadding(0, dp(8), 0, dp(10))
        }
        statusView = TextView(this).apply {
            text = "正在加载剧集…"
            textSize = 11f
            setTextColor(muted)
        }
        details.addView(name)
        details.addView(descriptionView)
        details.addView(statusView)
        headerContent.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(headerContent)
        content.addView(header)

        followButton = MaterialButton(this).apply {
            text = "管理追番状态"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { showFollowStatusMenu() }
        }
        content.addView(followButton, LinearLayout.LayoutParams(-1, dp(42)).apply {
            topMargin = dp(10)
        })
        val sectionTitle = TextView(this).apply {
            text = "全部剧集"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            setPadding(dp(2), dp(18), dp(2), dp(8))
        }
        content.addView(sectionTitle)
        episodeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(episodeList)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (cover.isNotBlank()) {
            val glideUrl = GlideUrl(cover, LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/").build())
            Glide.with(this).load(glideUrl).placeholder(R.drawable.bg_comment_avatar)
                .error(R.drawable.bg_comment_avatar).centerCrop().into(coverView)
        } else coverView.setImageResource(R.drawable.bg_comment_avatar)
    }

    private fun loadSeason() {
        if (loading) return
        loading = true
        statusView.text = "正在获取番剧信息…"
        lifecycleScope.launch {
            try {
                val season = withContext(Dispatchers.IO) { BangumiApiBridge.season(seasonId) }
                titleView.text = season.title.ifBlank { titleView.text }
                descriptionView.text = season.description.ifBlank { "暂无简介" }
                episodeList.removeAllViews()
                season.episodes.forEachIndexed { index, episode -> addEpisode(index, episode) }
                statusView.text = if (season.episodes.isEmpty()) "暂时没有可播放的剧集" else "共 ${season.episodes.size} 集"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                statusView.text = error.message ?: "番剧信息加载失败"
            } finally {
                loading = false
            }
        }
    }

    private fun addEpisode(index: Int, episode: BangumiEpisode) {
        val card = MaterialCardView(this).apply {
            radius = dp(11).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = dp(1)
            strokeColor = Color.rgb(237, 239, 242)
            isClickable = true
            isFocusable = true
            setOnClickListener { playEpisode(episode) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        val title = TextView(this).apply {
            text = "第 ${index + 1} 集  " + episode.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(38, 42, 49))
            maxLines = 2
        }
        val sub = TextView(this).apply {
            text = episode.longTitle.ifBlank { if (episode.durationMs > 0L) (episode.durationMs / 60000L).toString() + " 分钟" else "点击播放" }
            textSize = 11f
            setTextColor(Color.rgb(132, 139, 150))
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(title)
        row.addView(sub)
        card.addView(row)
        episodeList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(7)
        })
    }

    private fun playEpisode(episode: BangumiEpisode) {
        startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra(VideoPlayerActivity.EXTRA_EP_ID, episode.id)
            putExtra(VideoPlayerActivity.EXTRA_SEASON_ID, seasonId)
            putExtra(VideoPlayerActivity.EXTRA_AID, episode.aid)
            putExtra(VideoPlayerActivity.EXTRA_BVID, episode.bvid)
            putExtra(VideoPlayerActivity.EXTRA_COVER, episode.cover.ifBlank { cover })
            putExtra(VideoPlayerActivity.EXTRA_AUTHOR, "番剧 · " + titleView.text)
            putExtra(VideoPlayerActivity.EXTRA_TITLE,
                listOf(titleView.text.toString(), episode.title, episode.longTitle)
                    .filter { it.isNotBlank() }.joinToString(" · "))
        })
    }

    private fun showFollowStatusMenu() {
        val cookie = BiliSession.cookie(this)
        if (cookie.isBlank()) {
            startActivity(Intent(this, QrLoginActivity::class.java))
            return
        }
        val labels = arrayOf("想看", "在看", "看过", "取消追番")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("管理追番状态")
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BiliAccountApi.changeBangumiStatus(seasonId.toString(), when (which) {
                                0 -> 1
                                1 -> 2
                                2 -> 3
                                else -> 0
                            })
                        }
                        statusView.text = if (which == 3) "已取消追番" else "追番状态已更新"
                    } catch (error: Exception) {
                        statusView.text = error.message ?: "更新追番状态失败"
                    }
                }
            }.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

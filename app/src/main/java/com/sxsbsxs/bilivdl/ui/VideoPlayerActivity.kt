package com.sxsbsxs.bilivdl.ui

import com.sxsbsxs.bilivdl.R

import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.data.BiliPlaybackApi
import com.sxsbsxs.bilivdl.data.BangumiApiBridge
import com.sxsbsxs.bilivdl.data.SubtitleApiBridge
import com.sxsbsxs.bilivdl.data.BiliSubtitleTrack
import com.sxsbsxs.bilivdl.data.BiliInteractionApi
import com.sxsbsxs.bilivdl.data.BiliSession
import com.sxsbsxs.bilivdl.data.BilimiaoApiBridge
import com.sxsbsxs.bilivdl.data.BiliVideoActionApi
import com.sxsbsxs.bilivdl.data.BiliDownloadEngine
import com.sxsbsxs.bilivdl.data.BiliDownloadService
import com.sxsbsxs.bilivdl.data.YtDlpBridge
import com.sxsbsxs.bilivdl.data.PlaybackInfo
import com.sxsbsxs.bilivdl.data.VideoComment
import com.sxsbsxs.bilivdl.data.DanmakuItem
import com.sxsbsxs.bilivdl.databinding.ActivityVideoPlayerBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class VideoPlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_BVID = "bvid"
        const val EXTRA_AID = "aid"
        const val EXTRA_TITLE = "title"
        const val EXTRA_EP_ID = "ep_id"
        const val EXTRA_SEASON_ID = "season_id"
        const val EXTRA_COVER = "cover"
        const val EXTRA_AUTHOR = "author"
    }
    private lateinit var b: ActivityVideoPlayerBinding
    private var exo: ExoPlayer? = null
    private var info: PlaybackInfo? = null
    private lateinit var relatedAdapter: VideoAdapter
    private var videoBackupIndex = 0
    private var audioBackupIndex = 0
    private var selectedSubtitleUri: android.net.Uri? = null
    private var selectedSubtitleLabel: String = ""
    private var pendingSubtitleSrt = ""
    private val subtitleExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingSubtitleSrt) }
                    ?: error("无法写入字幕文件")
            }.onSuccess { b.playerStatus.text = "字幕已导出" }
             .onFailure { b.playerStatus.text = "字幕导出失败：${it.message}" }
        }
    }
    private var danmakuEnabled = true
    private var danmakuLoadFailed = false
    private var loadedDanmaku: List<DanmakuItem> = emptyList()
    private var pendingDanmakuXml = ""
    private val danmakuExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(pendingDanmakuXml) }
                    ?: error("无法写入弹幕文件")
            }.onSuccess { b.playerStatus.text = "弹幕 XML 已导出" }
             .onFailure { b.playerStatus.text = "弹幕导出失败：${it.message}" }
        }
    }
    private var commentPage = 1
    private var commentHasMore = true
    private var commentLoading = false
    private var consecutiveEmptyCommentPages = 0
    private var accountIsVip = false
    private var accountIsLoggedIn = false
    private var commentSort = 2
    private var videoLiked = false
    private var currentOwnerMid = 0L
    private var playbackSpeedIndex = 0
    private var pendingSeekPosition = 0L
    private var selectedPartIndex = 0
    private var resumePosition = 0L
    private val playerUiHandler = Handler(Looper.getMainLooper())
    private var controlsHideRunnable: Runnable? = null
    private var longPressRunnable: Runnable? = null
    private var pendingSingleTap: Runnable? = null
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureMoved = false
    private var temporarySpeed = false
    private var fullscreen = false
    private var lastTapAt = 0L
    private var updatingSeek = false
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (!isFinishing && ::b.isInitialized) {
                val player = exo
                val seek = findViewById<SeekBar>(R.id.player_seek)
                if (player != null && seek != null && !updatingSeek && player.duration > 0L) {
                    seek.max = 1000
                    seek.progress = ((player.currentPosition.toDouble() / player.duration).coerceIn(0.0, 1.0) * 1000).toInt()
                    findViewById<TextView>(R.id.player_time)?.text =
                        "${timeLabel(player.currentPosition)} / ${timeLabel(player.duration)}"
                }
                playerUiHandler.postDelayed(this, 500L)
            }
        }
    }
    private val playbackSpeeds = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 0.75f)
    private val bvid by lazy { intent.getStringExtra(EXTRA_BVID).orEmpty() }
    private val aid by lazy { intent.getLongExtra(EXTRA_AID, 0L) }
    private val epId by lazy { intent.getLongExtra(EXTRA_EP_ID, 0L) }
    private val seasonId by lazy { intent.getLongExtra(EXTRA_SEASON_ID, 0L) }
    private val cookie by lazy { BiliSession.cookie(this) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        b = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.playerFrame.post { fitPlayerFrame() }
        setupPlayerControls()
        b.playerTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        b.playerAuthor.text = intent.getStringExtra(EXTRA_AUTHOR).orEmpty().ifBlank { "哔哩哔哩视频" }
        b.playerAuthor.setOnClickListener { if (currentOwnerMid > 0L) openUserSpace(currentOwnerMid) }
        b.playerAuthorAvatar.setOnClickListener { if (currentOwnerMid > 0L) openUserSpace(currentOwnerMid) }
        resumePosition = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(positionKey(), 0L)
        b.commentSort.text = "按热度"
        b.commentsList.layoutManager = LinearLayoutManager(this)
        b.commentsList.adapter = CommentAdapter(::showReplies, ::likeComment, ::openUserSpace)
        relatedAdapter = VideoAdapter { video ->
            startActivity(Intent(this, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_BVID, video.bvid)
                putExtra(EXTRA_AID, video.aid)
                putExtra(EXTRA_TITLE, video.title)
                putExtra(EXTRA_COVER, video.cover)
                putExtra(EXTRA_AUTHOR, video.author)
            })
        }
        b.relatedVideosList.layoutManager = LinearLayoutManager(this)
        b.relatedVideosList.adapter = relatedAdapter
        b.playerInfoTab.setOnClickListener { showDetailTab(true) }
        b.playerCommentsTab.setOnClickListener { showDetailTab(false) }
        showDetailTab(true)
        loadRelatedVideos()
        b.qualityButton.setOnClickListener { showQuality() }
        b.partButton.setOnClickListener { showParts() }
        b.playerDownload.setOnClickListener { showDownloadQuality() }
        b.shareVideoButton.setOnClickListener { shareCurrentVideo() }
        b.playbackSpeedButton.setOnClickListener { cyclePlaybackSpeed() }
        b.subtitleButton.setOnClickListener { showSubtitles() }
        b.ytdlpDownloadButton.setOnClickListener { promptYtDlpUrl() }
        b.danmakuButton.setOnClickListener {
            if (danmakuLoadFailed) {
                info?.cid?.takeIf { it > 0L }?.let(::loadDanmaku)
            } else {
                danmakuEnabled = !danmakuEnabled
                b.danmakuOverlay.setDanmakuEnabled(danmakuEnabled)
                b.danmakuButton.text = if (danmakuEnabled) "弹幕：开 · 长按导出" else "弹幕：关"
            }
        }
        b.danmakuButton.setOnLongClickListener {
            exportDanmaku()
            true
        }
        b.commentSort.setOnClickListener {
            commentSort = if (commentSort == 2) 0 else 2
            b.commentSort.text = if (commentSort == 2) "按热度" else "按时间"
            resetComments()
        }
        b.commentSend.setOnClickListener { sendComment() }
        b.videoLike.setOnClickListener { toggleVideoLike() }
        b.videoCoin.setOnClickListener { showCoinDialog() }
        b.videoFavorite.setOnClickListener { showFavoriteFolders() }
        b.danmakuSend.setOnClickListener { showDanmakuComposer() }
        // comments_list is inside a NestedScrollView with nested scrolling disabled;
        // listen to the parent scroll view or the RecyclerView never receives scroll events.
        b.playerScroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { scroll, _, y, _, _ ->
                val content = scroll.getChildAt(0) ?: return@OnScrollChangeListener
                if (b.playerCommentsPanel.visibility == View.VISIBLE &&
                    y + scroll.height >= content.measuredHeight - 650) {
                    loadComments(false)
                }
            }
        )
        load(127)
        loadComments(true)
    }

    private fun showDetailTab(showInfo: Boolean) {
        b.playerInfoPanel.visibility = if (showInfo) View.VISIBLE else View.GONE
        b.playerCommentsPanel.visibility = if (showInfo) View.GONE else View.VISIBLE
        b.playerInfoTab.isSelected = showInfo
        b.playerCommentsTab.isSelected = !showInfo
        b.playerInfoTab.alpha = if (showInfo) 1f else 0.68f
        b.playerCommentsTab.alpha = if (showInfo) 0.68f else 1f
        b.playerInfoTab.setTextColor(getColor(if (showInfo) R.color.brand else R.color.text_secondary))
        b.playerCommentsTab.setTextColor(getColor(if (showInfo) R.color.text_secondary else R.color.brand))
    }

    private fun loadRelatedVideos() {
        b.relatedVideosStatus.text = "正在加载相关视频…"
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    BiliPlaybackApi.relatedVideos(bvid, aid, cookie)
                }
                relatedAdapter.submitList(items)
                b.relatedVideosStatus.text = if (items.isEmpty()) "暂时没有相关推荐" else ""
                b.relatedVideosStatus.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (error: Exception) {
                b.relatedVideosStatus.visibility = View.VISIBLE
                b.relatedVideosStatus.text = "相关推荐加载失败"
            }
        }
    }

    private fun promptYtDlpUrl() {
        val input = EditText(this).apply {
            hint = "粘贴支持的网站视频链接"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(24, 18, 24, 18)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("yt-dlp 通用下载")
            .setMessage("输入视频页面链接。B站高画质下载仍建议使用原生下载入口。")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("开始下载") { _, _ ->
                val url = input.text?.toString()?.trim().orEmpty()
                if (url.isNotBlank()) downloadWithYtDlp(url)
            }
            .show()
    }

    private fun downloadWithYtDlp(url: String) {
        b.downloadStatus.visibility = View.VISIBLE
        b.downloadStatus.text = "yt-dlp 正在解析并下载…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { YtDlpBridge.download(this@VideoPlayerActivity, url, BiliSession.cookie(this@VideoPlayerActivity)) }
                b.downloadStatus.text = "下载完成，已保存到系统视频库"
            } catch (error: Exception) {
                b.downloadStatus.text = "yt-dlp 下载失败：" + (error.message ?: "暂不支持此链接")
            }
        }
    }

    private fun formatCount(value: Long): String {
        if (value < 10_000L) return value.toString()
        val divisor = if (value >= 100_000_000L) 100_000_000L else 10_000L
        val suffix = if (divisor == 100_000_000L) "亿" else "万"
        val tenths = value / (divisor / 10L)
        val whole = tenths / 10L
        val fraction = tenths % 10L
        return if (fraction == 0L) "$whole$suffix" else "$whole.$fraction$suffix"
    }

    private fun load(qn: Int) {
        b.playerStatus.visibility = View.VISIBLE
        b.playerStatus.text = "正在获取播放地址…"
        lifecycleScope.launch {
            try {
                val previousQualities = info?.qualities.orEmpty()
                val accountProfile = if (cookie.isNotBlank()) withContext(Dispatchers.IO) {
                    runCatching { BiliSession.profile(this@VideoPlayerActivity, cookie) }.getOrNull()
                } else null
                val isVip = (accountProfile?.optInt("vipType") ?: 0) > 0
                val isLoggedIn = accountProfile?.optBoolean("isLogin") == true
                accountIsVip = isVip
                accountIsLoggedIn = isLoggedIn
                val loadedPlayback = withContext(Dispatchers.IO) {
                    if (epId > 0L) {
                        b.playerStatus.post { b.playerStatus.text = "正在获取番剧播放地址…" }
                        BangumiApiBridge.playback(epId, qn)
                    } else BiliPlaybackApi.load(
                        bvid, aid, qn, cookie, selectedPartIndex, isVip = isVip, isLoggedIn = isLoggedIn,
                        onStage = { stage -> runOnUiThread {
                            if (!isFinishing && ::b.isInitialized) {
                                b.playerStatus.visibility = View.VISIBLE
                                b.playerStatus.text = stage
                            }
                        } }
                    )
                }
                // Keep the full quality menu from the original highest-quality response.
                // A low-qn response is intentionally filtered by the API and must not erase higher choices.
                val lockedQualityIds = loadedPlayback.restrictedQualities.map { it.id }.toSet()
                val p = loadedPlayback.copy(qualities = (loadedPlayback.qualities + previousQualities)
                    .filterNot { it.first in lockedQualityIds }
                    .distinctBy { it.first }.sortedByDescending { it.first })
                info = p
                if (p.title.isNotBlank()) b.playerTitle.text = p.title
                videoLiked = if (cookie.isNotBlank() && aid > 0L) {
                    runCatching { withContext(Dispatchers.IO) { BiliVideoActionApi.isLiked(aid, cookie) } }.getOrDefault(false)
                } else false
                b.videoLike.text = (if (videoLiked) "已赞 " else "点赞 ") + formatCount(p.likeCount)
                b.videoCoin.text = "投币 " + formatCount(p.coinCount)
                b.videoFavorite.text = "收藏 " + formatCount(p.favoriteCount)
                b.shareVideoButton.text = "分享 " + formatCount(p.shareCount)
                b.commentsTitle.text = "评论区 · " + formatCount(p.replyCount)
                b.playerExtraStats.text = listOf(
                    p.category,
                    if (p.duration > 0) "${p.duration / 60}:${(p.duration % 60).toString().padStart(2, '0')}" else ""
                ).filter { it.isNotBlank() }.joinToString("  ·  ")
                val authorText = p.author.ifBlank { intent.getStringExtra(EXTRA_AUTHOR).orEmpty() }
                if (authorText.isNotBlank()) b.playerAuthor.text = authorText
                b.playerAuthorSign.text = p.ownerSign
                b.playerAuthorSign.visibility = if (p.ownerSign.isBlank()) View.GONE else View.VISIBLE
                b.playerAuthor.setOnClickListener { if (p.ownerMid > 0L) openUserSpace(p.ownerMid) }
                b.playerAuthorAvatar.setOnClickListener { if (p.ownerMid > 0L) openUserSpace(p.ownerMid) }
                val face = p.ownerFace.trim().let { if (it.startsWith("//")) "https:$it" else it }
                if (face.isNotBlank()) Glide.with(this@VideoPlayerActivity)
                    .load(GlideUrl(face, LazyHeaders.Builder().addHeader("Referer", "https://www.bilibili.com/").build()))
                    .placeholder(com.sxsbsxs.bilivdl.R.drawable.bg_comment_avatar)
                    .error(com.sxsbsxs.bilivdl.R.drawable.bg_comment_avatar).circleCrop().into(b.playerAuthorAvatar)
                b.playerStats.text = listOf(
                    formatCount(p.viewCount) + " 播放",
                    formatCount(p.danmakuCount) + " 弹幕",
                    formatCount(p.likeCount) + " 点赞",
                    formatCount(p.coinCount) + " 投币",
                    formatCount(p.favoriteCount) + " 收藏",
                    formatCount(p.shareCount) + " 分享",
                    p.pubdate.takeIf { it > 0L }?.let {
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(it * 1000))
                    }.orEmpty()
                ).filter { it.isNotBlank() }.joinToString("  ·  ")
                b.playerDescription.text = p.description
                b.playerDescription.visibility = if (p.description.isBlank()) View.GONE else View.VISIBLE
                b.playerDescription.maxLines = 2
                b.playerDescription.setOnClickListener {
                    b.playerDescription.maxLines = if (b.playerDescription.maxLines == 2) 20 else 2
                }
                videoBackupIndex = 0
                audioBackupIndex = 0
                selectedPartIndex = p.selectedPart
                b.partButton.text = if (epId > 0L) "番剧选集" else if (p.parts.size > 1) "分P ${p.selectedPart + 1}/${p.parts.size} · ${p.parts[p.selectedPart].title}" else "单P视频"
                exo?.release()
                // B站 gRPC 下发的 CDN 地址按官方客户端 UA 校验；不要把账号 Cookie 泄漏给 CDN 域名。
                val headers = mapOf("Referer" to "https://www.bilibili.com/")
                val http = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 BiliDroid/1.41.0 (bbcallen@gmail.com)")
                    .setConnectTimeoutMs(20000).setReadTimeoutMs(25000)
                    .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers)
                val source = DefaultMediaSourceFactory(this@VideoPlayerActivity).setDataSourceFactory(http)
                exo = ExoPlayer.Builder(this@VideoPlayerActivity).setMediaSourceFactory(source).build().also { player ->
                    b.playerView.player = player
                    if (p.isDash && p.videoUrl != null && p.audioUrl != null) {
                        val videoSource = source.createMediaSource(buildMediaItem(p.videoUrl))
                        val audioSource = source.createMediaSource(buildMediaItem(p.audioUrl))
                        player.setMediaSource(MergingMediaSource(videoSource, audioSource))
                    } else if (p.isDash) {
                        player.setMediaItem(buildMediaItem(p.videoUrl ?: p.url))
                    } else if (p.segments.size > 1) {
                        // B站 DURL 分段必须按顺序连续播放，不能只播放第一段。
                        player.setMediaItems(p.segments.map { buildMediaItem(it) })
                    } else {
                        player.setMediaItem(buildMediaItem(p.url))
                    }
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            if (!tryNextCdn(player, p, source)) {
                                b.playerStatus.visibility = View.VISIBLE
                                b.playerStatus.text = "播放失败：${error.errorCodeName}，请检查网络或清晰度"
                            }
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                val targetPosition = if (pendingSeekPosition > 0L) pendingSeekPosition else resumePosition
                                if (targetPosition > 0L) player.seekTo(targetPosition)
                                pendingSeekPosition = 0L
                                resumePosition = 0L
                                player.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
                                updatePlayerControlLabels()
                                b.playerStatus.visibility = View.GONE
                                b.playerStatus.text = "正在播放 · ${p.name}"
                                findViewById<TextView>(R.id.player_gesture_status).text = p.name
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            b.danmakuOverlay.setPlaying(isPlaying)
                            updatePlayerControlLabels()
                        }
                    })
                    player.prepare()
                    player.playWhenReady = true
                }
                b.qualityButton.text = p.name
                b.playerStatus.text = "正在连接视频…"
                loadDanmaku(p.cid)
            } catch (e: Exception) {
                val reason = e.message.orEmpty()
                b.playerStatus.visibility = View.VISIBLE
                b.playerStatus.text = if (reason.contains("Conversion") && reason.contains("万")) {
                    "获取播放地址失败：B站返回了异常格式数据，已尝试备用线路，请稍后重试"
                } else "获取播放地址失败：${reason.ifBlank { "接口异常" }}"
            }
        }
    }

    /** 自动切换 B 站返回的备用 CDN，保留播放进度且不向 CDN 发送账号 Cookie。 */
    private fun tryNextCdn(player: ExoPlayer, playback: PlaybackInfo, source: DefaultMediaSourceFactory): Boolean {
        val nextVideo = playback.videoBackupUrls.getOrNull(videoBackupIndex)
        val nextAudio = playback.audioBackupUrls.getOrNull(audioBackupIndex)
        if (nextVideo == null && nextAudio == null) return false
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        val videoUrl = nextVideo ?: playback.videoUrl
        val audioUrl = nextAudio ?: playback.audioUrl
        if (nextVideo != null) videoBackupIndex++
        if (nextAudio != null) audioBackupIndex++
        return try {
            if (playback.isDash && videoUrl != null && audioUrl != null) {
                val videoSource = source.createMediaSource(buildMediaItem(videoUrl))
                val audioSource = source.createMediaSource(buildMediaItem(audioUrl))
                player.setMediaSource(MergingMediaSource(videoSource, audioSource))
            } else if (videoUrl != null) {
                player.setMediaItem(buildMediaItem(videoUrl))
            } else return false
            player.prepare()
            player.seekTo(position)
            player.playWhenReady = wasPlaying
            b.playerStatus.visibility = View.VISIBLE
            b.playerStatus.text = "正在切换备用线路…"
            true
        } catch (_: Exception) { false }
    }
    private fun buildMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val subtitle = selectedSubtitleUri
        if (subtitle != null) {
            builder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(subtitle)
                    .setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage("und")
                    .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }
        return builder.build()
    }

    private fun showSubtitles() {
        val playback = info ?: run { b.playerStatus.text = "请等待播放地址加载完成"; return }
        b.playerStatus.text = "正在获取字幕列表…"
        lifecycleScope.launch {
            try {
                val identifiers = withContext(Dispatchers.IO) {
                    if (epId > 0L) {
                        val episode = BangumiApiBridge.episode(epId)
                        episode.aid to episode.cid
                    } else aid to playback.cid
                }
                val tracks = withContext(Dispatchers.IO) {
                    SubtitleApiBridge.tracks(identifiers.first, identifiers.second, epId, seasonId)
                }
                val labels = listOf("关闭字幕") + tracks.map { "${it.label}（${it.language}）" }
                val chooser = MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                    .setTitle("选择字幕")
                    .setItems(labels.toTypedArray()) { _, which ->
                        lifecycleScope.launch {
                            try {
                                if (which == 0) {
                                    selectedSubtitleUri = null
                                    selectedSubtitleLabel = ""
                                    b.subtitleButton.text = "字幕：关闭"
                                } else {
                                    val track = tracks[which - 1]
                                    selectedSubtitleUri = withContext(Dispatchers.IO) {
                                        SubtitleApiBridge.downloadAsVtt(this@VideoPlayerActivity, track)
                                    }
                                    selectedSubtitleLabel = track.label
                                    b.subtitleButton.text = "字幕：${track.label}"
                                }
                                pendingSeekPosition = exo?.currentPosition ?: 0L
                                load(playback.quality)
                            } catch (e: Exception) {
                                b.playerStatus.text = "字幕加载失败：${e.message}"
                            }
                        }
                    }
                    .setNeutralButton("导出 SRT 字幕", null)
                    .create()
                chooser.setOnShowListener {
                    chooser.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        if (tracks.isEmpty()) {
                            b.playerStatus.text = "没有可导出的字幕"
                            chooser.dismiss()
                        } else {
                            MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                                .setTitle("选择要导出的字幕")
                                .setItems(tracks.map { "${it.label}（${it.language}）" }.toTypedArray()) { _, index ->
                                    val track = tracks[index]
                                    lifecycleScope.launch {
                                        try {
                                            b.playerStatus.text = "正在下载字幕并转换为 SRT…"
                                            pendingSubtitleSrt = withContext(Dispatchers.IO) {
                                                SubtitleApiBridge.downloadAsSrt(this@VideoPlayerActivity, track)
                                            }
                                            if (pendingSubtitleSrt.isBlank()) error("字幕内容为空")
                                            subtitleExportLauncher.launch("字幕_${track.language}.srt")
                                        } catch (e: Exception) {
                                            b.playerStatus.text = "字幕导出失败：${e.message}"
                                        }
                                    }
                                }.show()
                            chooser.dismiss()
                        }
                    }
                }
                chooser.show()
                b.playerStatus.text = if (tracks.isEmpty()) "当前视频没有可用字幕" else "已找到 ${tracks.size} 条字幕"
            } catch (e: Exception) {
                b.playerStatus.text = "字幕列表获取失败：${e.message}"
            }
        }
    }


    private fun shareCurrentVideo() {
        val url = when {
            epId > 0L -> "https://www.bilibili.com/bangumi/play/ep$epId"
            bvid.isNotBlank() -> "https://www.bilibili.com/video/$bvid"
            aid > 0L -> "https://www.bilibili.com/video/av$aid"
            else -> ""
        }
        if (url.isBlank()) {
            b.playerStatus.text = "当前视频没有可分享的链接"
            return
        }
        val shareText = buildString {
            val title = b.playerTitle.text?.toString().orEmpty()
            if (title.isNotBlank()) append(title).append("\\n")
            append(url)
        }
        // 记录登录账号的真实分享操作；游客仍然可以正常调起系统分享，不强制登录。
        if (cookie.isNotBlank() && aid > 0L) {
            lifecycleScope.launch {
                val recorded = runCatching {
                    withContext(Dispatchers.IO) { BiliVideoActionApi.share(aid, cookie) }
                }.isSuccess
                if (recorded) {
                    val updated = info?.copy(shareCount = (info?.shareCount ?: 0L) + 1L)
                    if (updated != null) {
                        info = updated
                        b.shareVideoButton.text = "分享 " + formatCount(updated.shareCount)
                    }
                }
            }
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }, "分享视频"))
    }

    private fun showParts() {
        val p = info ?: return
        if (epId > 0L) {
            b.playerStatus.text = "正在获取番剧剧集…"
            lifecycleScope.launch {
                try {
                    val season = withContext(Dispatchers.IO) { BangumiApiBridge.season(seasonId, epId) }
                    if (season.episodes.isEmpty()) error("没有获取到本季剧集")
                    val labels = season.episodes.map { ep ->
                        listOf(ep.title, ep.longTitle).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    }.toTypedArray()
                    val selected = season.episodes.indexOfFirst { it.id == epId }.coerceAtLeast(0)
                    MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                        .setTitle(season.title.ifBlank { "选择番剧剧集" })
                        .setSingleChoiceItems(labels, selected) { dialog, which ->
                            dialog.dismiss()
                            val ep = season.episodes[which]
                            if (ep.id != epId) {
                                startActivity(android.content.Intent(this@VideoPlayerActivity, VideoPlayerActivity::class.java).apply {
                                    putExtra(EXTRA_EP_ID, ep.id)
                                    putExtra(EXTRA_SEASON_ID, season.seasonId)
                                    putExtra(EXTRA_TITLE, listOf(season.title, ep.title, ep.longTitle).filter { it.isNotBlank() }.joinToString(" · "))
                                })
                            }
                        }.setNegativeButton("取消", null).show()
                    b.playerStatus.text = "番剧剧集已加载"
                } catch (e: Exception) {
                    b.playerStatus.text = "番剧剧集加载失败：${e.message}"
                }
            }
            return
        }
        if (p.parts.size <= 1) {
            b.playerStatus.text = "当前视频只有一个分P"
            return
        }
        val labels = p.parts.map { part ->
            "P${part.page} · ${part.title}" + if (part.duration > 0) " · ${part.duration / 60}分${part.duration % 60}秒" else ""
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择视频分P")
            .setSingleChoiceItems(labels, p.selectedPart) { dialog, which ->
                dialog.dismiss()
                if (which != selectedPartIndex) {
                    selectedPartIndex = which
                    pendingSeekPosition = 0L
                    resumePosition = 0L
                    load(p.quality)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupPlayerControls() {
        val layer = findViewById<View>(R.id.player_gesture_layer)
        val play = findViewById<TextView>(R.id.player_play_pause)
        val back = findViewById<TextView>(R.id.player_back10)
        val forward = findViewById<TextView>(R.id.player_forward10)
        val speed = findViewById<TextView>(R.id.player_speed)
        val full = findViewById<TextView>(R.id.player_fullscreen)
        val seek = findViewById<SeekBar>(R.id.player_seek)
        play.setOnClickListener { exo?.let { if (it.isPlaying) it.pause() else it.play() }; updatePlayerControlLabels() }
        back.setOnClickListener { seekBy(-10_000L) }
        forward.setOnClickListener { seekBy(10_000L) }
        speed.setOnClickListener { cyclePlaybackSpeed(); updatePlayerControlLabels() }
        full.setOnClickListener { toggleFullscreen() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { updatingSeek = true }
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) findViewById<TextView>(R.id.player_time).text =
                    "${timeLabel((exo?.duration?.coerceAtLeast(0L) ?: 0L) * value / 1000L)} / ${timeLabel(exo?.duration ?: 0L)}"
            }
            override fun onStopTrackingTouch(bar: SeekBar) {
                val player = exo
                if (player != null && player.duration > 0L) player.seekTo(player.duration * bar.progress / 1000L)
                updatingSeek = false
            }
        })
        layer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureDownX = event.x
                    gestureDownY = event.y
                    gestureMoved = false
                    temporarySpeed = false
                    longPressRunnable?.let(playerUiHandler::removeCallbacks)
                    longPressRunnable = Runnable {
                        temporarySpeed = true
                        val current = exo?.playbackParameters?.speed ?: 1f
                        exo?.setPlaybackSpeed((current * 2f).coerceAtMost(3f))
                        showGestureStatus("长按加速 · ${(current * 2f).coerceAtMost(3f)}x")
                    }.also { playerUiHandler.postDelayed(it, 420L) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureDownX
                    val dy = event.y - gestureDownY
                    if (kotlin.math.abs(dx) > dp(18) || kotlin.math.abs(dy) > dp(18)) {
                        gestureMoved = true
                        longPressRunnable?.let(playerUiHandler::removeCallbacks)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let(playerUiHandler::removeCallbacks)
                    val dx = event.x - gestureDownX
                    val dy = event.y - gestureDownY
                    if (temporarySpeed) {
                        exo?.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
                        showGestureStatus("已恢复 ${playbackSpeeds[playbackSpeedIndex]}x")
                    } else if (gestureMoved && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        val duration = exo?.duration?.takeIf { it > 0L } ?: 0L
                        val jump = (dx / view.width.coerceAtLeast(1) * duration.coerceAtMost(120_000L)).toLong()
                        seekBy(jump)
                        showGestureStatus(if (jump >= 0) "快进 ${timeLabel(jump)}" else "快退 ${timeLabel(-jump)}")
                    } else if (gestureMoved) {
                        if (gestureDownX < view.width / 2f) {
                            val attrs = window.attributes
                            val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
                            attrs.screenBrightness = (current - dy / view.height.coerceAtLeast(1) * 1.4f).coerceIn(0.05f, 1f)
                            window.attributes = attrs
                            showGestureStatus("亮度 ${(attrs.screenBrightness * 100).toInt()}%")
                        } else {
                            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val steps = (kotlin.math.abs(dy) / dp(34)).toInt().coerceAtLeast(1)
                            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val next = (current + if (dy < 0) steps else -steps).coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                            showGestureStatus("音量 ${next * 100 / max}%")
                        }
                    } else {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastTapAt < 280L) {
                            pendingSingleTap?.let(playerUiHandler::removeCallbacks)
                            pendingSingleTap = null
                            when {
                                event.x < view.width / 3f -> seekBy(-10_000L)
                                event.x > view.width * 2f / 3f -> seekBy(10_000L)
                                else -> { exo?.let { if (it.isPlaying) it.pause() else it.play() }; updatePlayerControlLabels() }
                            }
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                            pendingSingleTap = Runnable { togglePlayerControls() }
                            playerUiHandler.postDelayed(pendingSingleTap!!, 280L)
                        }
                    }
                    scheduleControlsHide()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let(playerUiHandler::removeCallbacks)
                    if (temporarySpeed) exo?.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
                    true
                }
                else -> true
            }
        }
        updatePlayerControlLabels()
        playerUiHandler.removeCallbacks(progressUpdater)
        playerUiHandler.post(progressUpdater)
        scheduleControlsHide()
    }

    private fun timeLabel(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return if (seconds >= 3600) "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
        else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun seekBy(delta: Long) {
        val player = exo ?: return
        val target = (player.currentPosition + delta).coerceIn(0L, player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        player.seekTo(target)
        showGestureStatus(if (delta >= 0) "快进" else "快退")
    }

    private fun showGestureStatus(message: String) {
        findViewById<TextView>(R.id.player_gesture_status).apply {
            text = message
            visibility = View.VISIBLE
        }
        scheduleControlsHide()
    }

    private fun updatePlayerControlLabels() {
        if (!::b.isInitialized) return
        findViewById<TextView>(R.id.player_play_pause)?.text = if (exo?.isPlaying == true) "暂停" else "播放"
        findViewById<TextView>(R.id.player_speed)?.text = "${playbackSpeeds[playbackSpeedIndex]}x"
    }

    private fun togglePlayerControls() {
        val controls = findViewById<View>(R.id.player_controls)
        controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (controls.visibility == View.VISIBLE) scheduleControlsHide()
    }

    private fun scheduleControlsHide() {
        controlsHideRunnable?.let(playerUiHandler::removeCallbacks)
        controlsHideRunnable = Runnable {
            if (exo?.isPlaying == true) {
                findViewById<View>(R.id.player_controls).visibility = View.GONE
            }
        }.also { playerUiHandler.postDelayed(it, 3500L) }
    }

    private fun toggleFullscreen() {
        val frame = findViewById<View>(R.id.player_frame)
        val content = b.playerScroll.getChildAt(0) as? LinearLayout ?: return
        val details = content.getChildAt(1)
        fullscreen = !fullscreen
        if (fullscreen) {
            details.visibility = View.GONE
            content.layoutParams = content.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            frame.layoutParams = frame.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            findViewById<TextView>(R.id.player_fullscreen).text = "退出全屏"
        } else {
            details.visibility = View.VISIBLE
            content.layoutParams = content.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            frame.post { frame.post { fitPlayerFrame() } }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            findViewById<TextView>(R.id.player_fullscreen).text = "全屏"
        }
    }

    private fun fitPlayerFrame() {
        if (!::b.isInitialized || fullscreen) return
        val width = b.playerFrame.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        b.playerFrame.layoutParams = b.playerFrame.layoutParams.apply {
            height = (width * 9f / 16f).toInt().coerceAtLeast(dp(180))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun cyclePlaybackSpeed() {
        val player = exo ?: run { b.playerStatus.text = "播放器尚未准备好"; return }
        playbackSpeedIndex = (playbackSpeedIndex + 1) % playbackSpeeds.size
        val speed = playbackSpeeds[playbackSpeedIndex]
        player.setPlaybackSpeed(speed)
        b.playbackSpeedButton.text = "倍速 ${speed}x"
    }

    private fun showQuality() {
        val p = info ?: return
        val locked = p.restrictedQualities.associateBy { it.id }
        val options = (p.qualities.map { it.first to it.second } + locked.values.map { it.id to it.label })
            .distinctBy { it.first }.sortedByDescending { it.first }
        if (options.isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle("选择播放清晰度")
            .setItems(options.map { (id, label) ->
                val gate = locked[id]
                label + when {
                    gate?.requiresVip == true -> "  🔒大会员专享"
                    gate?.requiresLogin == true -> "  🔒登录后可用"
                    else -> ""
                }
            }.toTypedArray()) { _, index ->
                val selected = options[index]
                val gate = locked[selected.first]
                if (gate != null) {
                    android.widget.Toast.makeText(this,
                        if (gate.requiresVip) "该清晰度需要大会员" else "该清晰度需要登录",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else if (selected.first != p.quality) {
                    pendingSeekPosition = exo?.currentPosition ?: 0L
                    load(selected.first)
                }
            }.show()
    }

    private fun showDownloadQuality() {
        val p = info
        if (p == null) { b.downloadStatus.visibility = View.VISIBLE
        b.downloadStatus.text = "请先等待播放地址加载完成"; return }
        b.downloadStatus.visibility = View.VISIBLE
        b.downloadStatus.text = "正在读取可用清晰度…"
        lifecycleScope.launch {
            try {
                val downloadedChoices = withContext(Dispatchers.IO) {
                    if (epId <= 0L && aid > 0L) {
                        // Download probing can be capped independently from playback (BiliMiao/gRPC).
                        runCatching { BiliPlaybackApi.downloadQualities(aid, p.cid, cookie, isVip = accountIsVip, isLoggedIn = accountIsLoggedIn) }.getOrDefault(emptyList())
                    } else emptyList()
                }
                // Merge verified playback tiers with download-endpoint tiers; never discard
                // a 720P/1080P track that playback already proved is available.
                val choices = (p.qualities + downloadedChoices)
                    .distinctBy { it.first }.sortedByDescending { it.first }
                val locked = p.restrictedQualities.associateBy { it.id }
                val allOptions = (choices.map { it.first to it.second } + locked.values.map { it.id to it.label })
                    .distinctBy { it.first }.sortedByDescending { it.first }
                if (allOptions.isEmpty()) error("B站没有返回可用清晰度")
                if (choices.isEmpty()) {
                    MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                        .setTitle("当前账号暂无可下载画质")
                        .setMessage(allOptions.joinToString("\n") { (id, label) ->
                            label + if (locked[id]?.requiresVip == true) " · 需要大会员" else " · 需要登录"
                        })
                        .setPositiveButton("知道了", null)
                        .show()
                    b.downloadStatus.visibility = View.GONE
                    return@launch
                }
                var selectedQuality = choices.first().first
                val initialIndex = allOptions.indexOfFirst { it.first == selectedQuality }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(this@VideoPlayerActivity).setTitle("选择下载清晰度")
                    .setSingleChoiceItems(allOptions.map { (id, label) ->
                        label + when {
                            locked[id]?.requiresVip == true -> "  🔒大会员专享"
                            locked[id]?.requiresLogin == true -> "  🔒登录后可用"
                            id == choices.firstOrNull()?.first -> " · 推荐"
                            else -> ""
                        }
                    }.toTypedArray(), initialIndex) { _, which ->
                        val id = allOptions[which].first
                        val gate = locked[id]
                        if (gate != null) {
                            android.widget.Toast.makeText(this@VideoPlayerActivity,
                                if (gate.requiresVip) "该清晰度需要大会员" else "该清晰度需要登录",
                                android.widget.Toast.LENGTH_SHORT).show()
                        } else selectedQuality = id
                    }
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下一步") { _, _ -> loadDownloadStreamOptions(selectedQuality) }
                    .show()
                b.downloadStatus.visibility = View.GONE
                b.downloadStatus.text = ""
            } catch (error: Exception) {
                b.downloadStatus.text = "读取清晰度失败：${error.message ?: "网络异常"}"
            }
        }
    }

    private fun loadDownloadStreamOptions(qn: Int) {
        val p = info ?: return
        if (epId <= 0L && aid <= 0L) {
            startDownload(qn)
            return
        }
        b.downloadStatus.text = "正在读取可选音视频资源…"
        lifecycleScope.launch {
            try {
                val options = withContext(Dispatchers.IO) {
                    if (epId > 0L) BangumiApiBridge.downloadStreamChoices(epId, p.cid, qn) else BiliPlaybackApi.downloadStreamChoices(aid, p.cid, qn, cookie)
                }
                // The selected quality is a hard ceiling and must not silently turn into
                // a lower-resolution download just because this endpoint returned 480P/360P.
                val matchingVideos = options.video.filter { it.id == qn }
                if (matchingVideos.isEmpty()) {
                    startDownload(qn)
                    return@launch
                }
                val videoLabels = matchingVideos.map { it.label + if (it.bandwidth == matchingVideos.maxOf { v -> v.bandwidth }) " · 推荐" else "" }.toTypedArray()
                var videoIndex = 0
                MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                    .setTitle("选择视频资源")
                    .setSingleChoiceItems(videoLabels, 0) { _, which -> videoIndex = which }
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下一步") { _, _ ->
                        val selectedVideo = matchingVideos[videoIndex]
                        if (options.audio.isEmpty()) {
                            startDownload(qn, selectedVideo.id.toString() + "|" + selectedVideo.codec, "")
                        } else {
                            var audioIndex = 0
                            val audioLabels = options.audio.map {
                                it.label + if (it.bandwidth == options.audio.maxOf { a -> a.bandwidth }) " · 推荐" else ""
                            }.toTypedArray()
                            MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                                .setTitle("选择音频资源")
                                .setSingleChoiceItems(audioLabels, 0) { _, which -> audioIndex = which }
                                .setNegativeButton("取消", null)
                                .setPositiveButton("开始下载") { _, _ ->
                                    startDownload(qn, selectedVideo.id.toString() + "|" + selectedVideo.codec, options.audio[audioIndex].id.toString() + "|" + options.audio[audioIndex].codec)
                                }.show()
                        }
                    }.show()
            } catch (error: Exception) {
                b.downloadStatus.text = "读取资源选项失败，使用默认音视频轨道： " + (error.message ?: "未知错误")
                startDownload(qn)
            }
        }
    }

    private fun startDownload(qn: Int, videoCodec: String = "", audioCodec: String = "") {
        val taskId = java.util.UUID.randomUUID().toString()
        val title = b.playerTitle.text.toString()
            .replace(Regex("[\\\\/:*?\\\"<>|]"), "_")
            .take(70)
            .ifBlank { "bilibili_video" }
        val task = JSONObject()
            .put("id", taskId)
            .put("title", title)
            .put("quality", info?.qualities?.firstOrNull { it.first == qn }?.second ?: "清晰度 " + qn)
            .put("episodeId", epId)
            .put("pageIndex", selectedPartIndex)
            .put("cover", intent.getStringExtra(EXTRA_COVER).orEmpty())
            .put("author", intent.getStringExtra(EXTRA_AUTHOR).orEmpty())
            .put("bvid", bvid)
            .put("aid", aid)
            .put("qn", qn)
            .put("status", "downloading")
            .put("progress", "等待后台下载服务启动")
            .put("time", System.currentTimeMillis())
        val prefs = getSharedPreferences("download_queue", MODE_PRIVATE)
        val old = JSONArray(prefs.getString("tasks", "[]"))
        old.put(task)
        prefs.edit().putString("tasks", old.toString()).apply()
        getSharedPreferences("download_options", MODE_PRIVATE).edit()
            .putString(taskId + "_video", videoCodec)
            .putString(taskId + "_audio", audioCodec)
            .apply()
        try {
            BiliDownloadService.enqueue(this, taskId, bvid, aid, title, qn, epId, videoCodec, audioCodec, selectedPartIndex, intent.getStringExtra(EXTRA_COVER).orEmpty(), intent.getStringExtra(EXTRA_AUTHOR).orEmpty())
            b.downloadStatus.visibility = View.GONE
            b.downloadStatus.text = ""
        } catch (error: Exception) {
            task.put("status", "failed").put("progress", error.message ?: "无法启动下载服务")
            val current = JSONArray(prefs.getString("tasks", "[]"))
            val updated = JSONArray()
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                updated.put(if (item.optString("id") == taskId) task else item)
            }
            prefs.edit().putString("tasks", updated.toString()).apply()
            b.downloadStatus.visibility = View.VISIBLE
            b.downloadStatus.text = "下载任务启动失败：" + error.message
        }
    }

    private fun loadDanmaku(cid: Long) {
        danmakuLoadFailed = false
        b.danmakuButton.text = "弹幕加载中…"
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { BiliInteractionApi.danmaku(cid, cookie) }
                loadedDanmaku = items
                b.danmakuOverlay.setPlaybackPositionProvider { exo?.currentPosition ?: 0L }
                b.danmakuOverlay.setDanmaku(items)
                b.danmakuOverlay.setDanmakuEnabled(danmakuEnabled)
                // The player may already be playing when the network request finishes;
                // re-sync the overlay or it can draw only one frame and appear empty.
                b.danmakuOverlay.setPlaying(exo?.isPlaying == true)
                danmakuLoadFailed = false
                b.danmakuButton.text = if (items.isEmpty()) "暂无弹幕" else "弹幕：开（${items.size}）· 长按导出"
            } catch (e: Exception) {
                danmakuLoadFailed = true
                b.danmakuButton.text = "弹幕加载失败 · 点击重试"
                // A danmaku endpoint failure should not replace the playback status with a noisy warning.
            }
        }
    }


    private fun exportDanmaku() {
        if (loadedDanmaku.isEmpty()) {
            b.playerStatus.visibility = View.VISIBLE
            b.playerStatus.text = "当前视频还没有加载到弹幕"
            return
        }
        fun xml(value: String): String = value.replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        pendingDanmakuXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>\n""")
            append("<i><chatserver>chat.bilibili.com</chatserver><chatid>0</chatid><mission>0</mission><maxlimit>")
            append(loadedDanmaku.size).append("</maxlimit><state>0</state><real_name>0</real_name><source>k-v</source>\n")
            loadedDanmaku.forEach { item ->
                append("<d p=\"").append(item.timeMs / 1000.0).append(",").append(item.mode).append(",")
                    .append(item.fontSize).append(",").append(item.color).append(",0,")
                    .append(item.pool).append(",0,0\">").append(xml(item.text)).append("</d>\n")
            }
            append("</i>")
        }
        val file = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "视频" }
            .replace(Regex("""[\\\\/:*?"<>|]"""), "_").take(60)
        danmakuExportLauncher.launch("${file}_弹幕.xml")
    }

    private fun resetComments() {
        commentPage = 1
        commentHasMore = true
        consecutiveEmptyCommentPages = 0
        (b.commentsList.adapter as CommentAdapter).submit(emptyList())
        loadComments(true)
    }

    private fun loadComments(reset: Boolean) {
        if (commentLoading || (!commentHasMore && !reset)) return
        commentLoading = true
        b.commentsStatus.visibility = View.GONE
        lifecycleScope.launch {
            try {
                val page = commentPage
                val result = withContext(Dispatchers.IO) { BilimiaoApiBridge.commentsPage(aid, page, commentSort) }
                (b.commentsList.adapter as CommentAdapter).submit(result.comments, append = !reset)
                commentPage = page + 1
                consecutiveEmptyCommentPages = if (result.comments.isEmpty() && result.hasMore) consecutiveEmptyCommentPages + 1 else 0
                commentHasMore = result.hasMore && consecutiveEmptyCommentPages < 3
                b.commentsTitle.text = "评论区 · " + formatCount(result.total.toLong())
                b.commentsStatus.visibility = if (result.comments.isEmpty()) View.VISIBLE else View.GONE
                b.commentsStatus.text = when {
                    result.comments.isEmpty() && reset && !result.hasMore -> "暂无评论"
                    result.comments.isEmpty() && commentHasMore -> "这一页暂时没有评论，正在继续加载…"
                    result.comments.isEmpty() -> "暂时没有更多可展示的评论"
                    else -> ""
                }
                // RecyclerView 位于关闭嵌套滚动的 NestedScrollView 中，追加内容后主动检查是否仍接近底部。
                if (commentHasMore) b.playerScroll.post {
                    val content = b.playerScroll.getChildAt(0)
                    if (b.playerCommentsPanel.visibility == View.VISIBLE && content != null &&
                        b.playerScroll.scrollY + b.playerScroll.height >= content.measuredHeight - 500) {
                        loadComments(false)
                    }
                }
            } catch (e: Exception) {
                b.commentsStatus.visibility = View.VISIBLE
                b.commentsStatus.text = "评论加载失败，请稍后重试"
            } finally { commentLoading = false }
        }
    }

    private fun openUserSpace(mid: Long) {
        if (mid <= 0L) return
        startActivity(Intent(this, UserSpaceActivity::class.java).putExtra(UserSpaceActivity.EXTRA_MID, mid))
    }

    private fun showReplies(comment: VideoComment) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        lateinit var replyAdapter: CommentAdapter
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@VideoPlayerActivity)
            isNestedScrollingEnabled = true
        }
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(com.sxsbsxs.bilivdl.R.color.text_secondary))
            setPadding(4, 6, 4, 6)
            visibility = View.GONE
        }
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = "回复这条评论…"
            minLines = 1
            maxLines = 3
            textSize = 14f
            setPadding(12, 8, 12, 8)
            background = getDrawable(com.sxsbsxs.bilivdl.R.drawable.bg_empty_card)
        }
        val send = MaterialButton(this).apply {
            text = "发送"
            isAllCaps = false
            minHeight = 42
        }
        composer.addView(input, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(send, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 44))
        root.addView(list, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(composer, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("全部回复 · ${comment.replyCount}")
            .setView(root)
            .setNegativeButton("关闭", null)
            .create()
        replyAdapter = CommentAdapter(
            onReplies = { nested -> showReplies(nested) },
            onLike = { target ->
                lifecycleScope.launch {
                    try {
                        if (cookie.isBlank()) error("评论点赞需要先登录 B站账号")
                        withContext(Dispatchers.IO) { BilimiaoApiBridge.likeComment(aid, target.id, !target.liked) }
                        replyAdapter.updateLike(target.id, !target.liked)
                    } catch (error: Exception) {
                        status.visibility = View.VISIBLE
                        status.text = error.message ?: "点赞失败"
                    }
                }
            },
            onUser = ::openUserSpace
        )
        list.adapter = replyAdapter
        var page = 1
        var hasMore = true
        var loading = false
        var emptyPages = 0
        fun loadReplyPage(reset: Boolean) {
            if (loading || (!hasMore && !reset)) return
            if (reset) emptyPages = 0
            loading = true
            val requestedPage = if (reset) 1 else page
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        BilimiaoApiBridge.replies(aid, comment.id, requestedPage)
                    }
                    if (reset) replyAdapter.submit(listOf(comment) + result.comments)
                    else replyAdapter.submit(result.comments, append = true)
                    page = requestedPage + 1
                    emptyPages = if (result.comments.isEmpty() && result.hasMore) emptyPages + 1 else 0
                    hasMore = result.hasMore && emptyPages < 3
                    status.visibility = if (!hasMore || result.comments.isEmpty()) View.VISIBLE else View.GONE
                    status.text = when {
                        result.comments.isEmpty() && reset && !result.hasMore -> "暂无回复"
                        result.comments.isEmpty() && hasMore -> "这一页暂无回复，继续加载…"
                        !hasMore -> "没有更多回复了"
                        else -> ""
                    }
                    if (result.comments.isEmpty() && hasMore) status.post { loadReplyPage(false) }
                } catch (error: Exception) {
                    status.visibility = View.VISIBLE
                    status.text = "回复加载失败：${error.message ?: "网络异常"}"
                } finally {
                    loading = false
                }
            }
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && manager.findLastVisibleItemPosition() >= manager.itemCount - 3) {
                    loadReplyPage(false)
                }
            }
        })
        send.setOnClickListener {
            val message = input.text?.toString()?.trim().orEmpty()
            if (message.isBlank()) {
                input.error = "请输入回复内容"
                return@setOnClickListener
            }
            lifecycleScope.launch {
                send.isEnabled = false
                try {
                    if (cookie.isBlank()) error("回复评论需要先登录 B站账号")
                    withContext(Dispatchers.IO) {
                        BilimiaoApiBridge.sendReply(aid, comment.id, comment.id, message)
                    }
                    input.setText("")
                    page = 1
                    hasMore = true
                    replyAdapter.submit(listOf(comment))
                    loadReplyPage(true)
                } catch (error: Exception) {
                    status.visibility = View.VISIBLE
                    status.text = error.message ?: "回复发送失败"
                } finally {
                    send.isEnabled = true
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.72f).toInt()
        )
        loadReplyPage(true)
    }

    private fun likeComment(comment: VideoComment) {
        lifecycleScope.launch {
            try {
                if (cookie.isBlank()) error("评论点赞需要先使用 B站 APP 扫码登录")
                val result = withContext(Dispatchers.IO) { BilimiaoApiBridge.likeComment(aid, comment.id, !comment.liked) }
                (b.commentsList.adapter as CommentAdapter).updateLike(comment.id, !comment.liked)
                b.commentsStatus.visibility = View.GONE
            } catch (e: Exception) {
                b.commentsStatus.visibility = View.VISIBLE
                b.commentsStatus.text = e.message ?: "点赞失败"
            }
        }
    }

    private fun sendComment() {
        val text = b.commentInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) { b.commentsStatus.visibility = View.VISIBLE; b.commentsStatus.text = "先输入评论内容"; return }
        lifecycleScope.launch {
            try {
                if (cookie.isBlank()) error("发表评论需要先使用 B站 APP 扫码登录")
                val result = withContext(Dispatchers.IO) { BilimiaoApiBridge.sendComment(aid, text) }
                b.commentInput.text?.clear()
                b.commentsStatus.visibility = View.GONE
                resetComments()
            } catch (e: Exception) {
                b.commentsStatus.visibility = View.VISIBLE
                b.commentsStatus.text = e.message ?: "发送失败"
            }
        }
    }


    private fun requireAid(): Long {
        if (aid <= 0L) error("当前视频缺少 AV 号，无法执行互动")
        return aid
    }

    private fun toggleVideoLike() {
        lifecycleScope.launch {
            try {
                if (cookie.isBlank()) error("点赞需要先使用 B站 APP 扫码登录")
                val target = !videoLiked
                withContext(Dispatchers.IO) {
                    BilimiaoApiBridge.likeVideo(requireAid(), target)
                }
                videoLiked = target
                b.videoLike.text = (if (target) "已赞 " else "点赞 ") + formatCount(((info?.likeCount ?: 0L) + if (target) 1L else -1L).coerceAtLeast(0L))
                b.playerStatus.text = if (target) "视频点赞成功" else "已取消点赞"
            } catch (e: Exception) {
                b.playerStatus.text = e.message ?: "视频点赞失败"
            }
        }
    }

    private fun showCoinDialog() {
        if (cookie.isBlank()) { b.playerStatus.text = "投币需要先使用 B站 APP 扫码登录"; return }
        MaterialAlertDialogBuilder(this)
            .setTitle("给视频投币")
            .setItems(arrayOf("投 1 枚硬币", "投 2 枚硬币", "投币并点赞")) { _, which ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BilimiaoApiBridge.coin(requireAid(), if (which == 1) 2 else 1, which == 2)
                        }
                        if (which == 2) {
                            videoLiked = true
                            b.videoLike.text = "已赞 " + formatCount((info?.likeCount ?: 0L) + 1L)
                        }
                        b.playerStatus.text = if (which == 2) "投币并点赞成功" else "投币成功"
                    } catch (e: Exception) {
                        b.playerStatus.text = e.message ?: "投币失败"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFavoriteFolders() {
        if (cookie.isBlank()) {
            b.playerStatus.text = "收藏需要先登录 B站账号"
            return
        }
        b.playerStatus.text = "正在读取收藏夹…"
        lifecycleScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) {
                    BiliVideoActionApi.favoriteFolders(cookie)
                }
                if (folders.isEmpty()) {
                    b.playerStatus.text = "没有可用的收藏夹"
                    return@launch
                }
                MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                    .setTitle("选择收藏夹")
                    .setItems(folders.map { it.second }.toTypedArray()) { _, index ->
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    BiliVideoActionApi.addFavorite(requireAid(), folders[index].first, cookie)
                                }
                                b.playerStatus.text = "已收藏到「${folders[index].second}」"
                            } catch (e: Exception) {
                                b.playerStatus.text = e.message ?: "收藏失败"
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } catch (e: Exception) {
                b.playerStatus.text = e.message ?: "读取收藏夹失败"
            }
        }
    }

    private fun showDanmakuComposer() {
        val input = EditText(this).apply {
            hint = "输入弹幕内容"
            maxLines = 2
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("发送弹幕")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送") { _, _ ->
                val message = input.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    try {
                        val current = exo?.currentPosition ?: 0L
                        val currentCid = info?.cid ?: error("播放信息尚未准备好")
                        withContext(Dispatchers.IO) {
                            BiliVideoActionApi.sendDanmaku(
                                requireAid(), currentCid, current, message, cookie
                            )
                        }
                        b.playerStatus.text = "弹幕发送成功"
                    } catch (e: Exception) {
                        b.playerStatus.text = e.message ?: "弹幕发送失败"
                    }
                }
            }.show()
    }

    private fun positionKey(): String = if (epId > 0L) "ep$epId" else (bvid.ifBlank { "av$aid" }) + "_p" + selectedPartIndex

    override fun onStop() {
        exo?.let { player ->
            getSharedPreferences("playback_positions", MODE_PRIVATE)
                .edit()
                .putLong(positionKey(), player.currentPosition.coerceAtLeast(0L))
                .apply()
            player.pause()
        }
        super.onStop()
    }
    override fun onDestroy() {
        playerUiHandler.removeCallbacks(progressUpdater)
        controlsHideRunnable?.let(playerUiHandler::removeCallbacks)
        longPressRunnable?.let(playerUiHandler::removeCallbacks)
        pendingSingleTap?.let(playerUiHandler::removeCallbacks)
        b.playerView.player = null
        exo?.release()
        exo = null
        super.onDestroy()
    }
}
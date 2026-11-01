package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
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
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import com.sxsbsxs.bilivdl.data.PlaybackInfo
import com.sxsbsxs.bilivdl.data.VideoComment
import com.sxsbsxs.bilivdl.data.DanmakuItem
import com.sxsbsxs.bilivdl.databinding.ActivityVideoPlayerBinding
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
    }
    private lateinit var b: ActivityVideoPlayerBinding
    private var exo: ExoPlayer? = null
    private var info: PlaybackInfo? = null
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
    private var commentSort = 3
    private var videoLiked = false
    private var playbackSpeedIndex = 0
    private var pendingSeekPosition = 0L
    private var selectedPartIndex = 0
    private var resumePosition = 0L
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
        b.playerTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        resumePosition = getSharedPreferences("playback_positions", MODE_PRIVATE).getLong(positionKey(), 0L)
        b.commentsList.layoutManager = LinearLayoutManager(this)
        b.commentsList.adapter = CommentAdapter(::showReplies, ::likeComment)
        b.qualityButton.setOnClickListener { showQuality() }
        b.partButton.setOnClickListener { showParts() }
        b.playerDownload.setOnClickListener { showDownloadQuality() }
        b.shareVideoButton.setOnClickListener { shareCurrentVideo() }
        b.playbackSpeedButton.setOnClickListener { cyclePlaybackSpeed() }
        b.subtitleButton.setOnClickListener { showSubtitles() }
        b.danmakuButton.setOnClickListener {
            danmakuEnabled = !danmakuEnabled
            b.danmakuOverlay.setDanmakuEnabled(danmakuEnabled)
            b.danmakuButton.text = if (danmakuEnabled) "弹幕：开 · 长按导出" else "弹幕：关"
        }
        b.danmakuButton.setOnLongClickListener {
            exportDanmaku()
            true
        }
        b.commentSort.setOnClickListener {
            commentSort = if (commentSort == 3) 2 else 3
            b.commentSort.text = if (commentSort == 3) "按热度" else "按时间"
            resetComments()
        }
        b.commentSend.setOnClickListener { sendComment() }
        b.videoLike.setOnClickListener { toggleVideoLike() }
        b.videoCoin.setOnClickListener { showCoinDialog() }
        b.videoFavorite.setOnClickListener { showFavoriteFolders() }
        b.videoTriple.setOnClickListener { tripleAction() }
        b.danmakuSend.setOnClickListener { showDanmakuComposer() }
        // comments_list is inside a NestedScrollView with nested scrolling disabled;
        // listen to the parent scroll view or the RecyclerView never receives scroll events.
        b.playerScroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { scroll, _, y, _, _ ->
                val content = scroll.getChildAt(0) ?: return@OnScrollChangeListener
                if (y + scroll.height >= content.measuredHeight - 650) {
                    loadComments(false)
                }
            }
        )
        load(127)
        loadComments(true)
    }

    private fun load(qn: Int) {
        b.playerStatus.text = "正在获取播放地址…"
        lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { if (epId > 0L) BangumiApiBridge.playback(epId, qn) else BiliPlaybackApi.load(bvid, aid, qn, cookie, selectedPartIndex) }
                info = p
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
                            b.playerStatus.text = "播放失败：${error.errorCodeName}。请检查网络或账号权限"
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) {
                                val targetPosition = if (pendingSeekPosition > 0L) pendingSeekPosition else resumePosition
                                if (targetPosition > 0L) player.seekTo(targetPosition)
                                pendingSeekPosition = 0L
                                resumePosition = 0L
                                player.setPlaybackSpeed(playbackSpeeds[playbackSpeedIndex])
                                b.playerStatus.text = "正在播放 · ${p.name}"
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            b.danmakuOverlay.setPlaying(isPlaying)
                        }
                    })
                    player.prepare()
                    player.playWhenReady = true
                }
                b.qualityButton.text = p.name
                b.playerStatus.text = "正在连接视频…"
                loadDanmaku(p.cid)
            } catch (e: Exception) {
                b.playerStatus.text = "获取播放地址失败：${e.message ?: "接口异常"}"
            }
        }
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

    private fun cyclePlaybackSpeed() {
        val player = exo ?: run { b.playerStatus.text = "播放器尚未准备好"; return }
        playbackSpeedIndex = (playbackSpeedIndex + 1) % playbackSpeeds.size
        val speed = playbackSpeeds[playbackSpeedIndex]
        player.setPlaybackSpeed(speed)
        b.playbackSpeedButton.text = "倍速 ${speed}x"
    }

    private fun showQuality() {
        val p = info ?: return
        if (p.qualities.isEmpty()) return
        MaterialAlertDialogBuilder(this).setTitle("选择播放清晰度")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, p.qualities.map { it.second }.toTypedArray())) { _, i ->
                if (p.qualities[i].first != p.quality) {
                    pendingSeekPosition = exo?.currentPosition ?: 0L
                    load(p.qualities[i].first)
                }
            }.show()
    }

    private fun showDownloadQuality() {
        val p = info
        if (p == null) { b.downloadStatus.text = "请先等待播放地址加载完成"; return }
        val choices = (if (p.qualities.isEmpty()) listOf(p.quality to p.name) else p.qualities)
            .distinctBy { it.first }.sortedByDescending { it.first }
        var selected = 0
        MaterialAlertDialogBuilder(this).setTitle("选择下载清晰度")
            .setSingleChoiceItems(choices.map { it.second + if (it.first == choices.first().first) " · 推荐" else "" }.toTypedArray(), 0) { _, which ->
                selected = which
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("下一步") { _, _ -> loadDownloadStreamOptions(choices[selected].first) }
            .show()
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
                    if (epId > 0L) BangumiApiBridge.downloadStreamChoices(epId, p.cid, qn) else BiliPlaybackApi.downloadStreamChoices(aid, p.cid, qn)
                }
                if (options.video.isEmpty()) {
                    startDownload(qn)
                    return@launch
                }
                val videoLabels = options.video.map { it.label + if (it.bandwidth == options.video.maxOf { v -> v.bandwidth }) " · 推荐" else "" }.toTypedArray()
                var videoIndex = 0
                MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                    .setTitle("选择视频资源")
                    .setSingleChoiceItems(videoLabels, 0) { _, which -> videoIndex = which }
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下一步") { _, _ ->
                        val selectedVideo = options.video[videoIndex]
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
            .put("quality", info?.name ?: "清晰度 " + qn)
            .put("episodeId", epId)
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
            BiliDownloadService.enqueue(this, taskId, bvid, aid, title, qn, epId, videoCodec, audioCodec)
            b.downloadStatus.text = "已加入后台下载 · " + (info?.name ?: "清晰度 " + qn) +
                if (videoCodec.isNotBlank()) " · " + videoCodec else ""
        } catch (error: Exception) {
            task.put("status", "failed").put("progress", error.message ?: "无法启动下载服务")
            val current = JSONArray(prefs.getString("tasks", "[]"))
            val updated = JSONArray()
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                updated.put(if (item.optString("id") == taskId) task else item)
            }
            prefs.edit().putString("tasks", updated.toString()).apply()
            b.downloadStatus.text = "下载任务启动失败：" + error.message
        }
    }

    private fun loadDanmaku(cid: Long) {
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { BiliInteractionApi.danmaku(cid, cookie) }
                loadedDanmaku = items
                b.danmakuOverlay.setDanmaku(items)
                b.danmakuOverlay.setPlaybackPositionProvider { exo?.currentPosition ?: 0L }
                b.danmakuOverlay.setDanmakuEnabled(danmakuEnabled)
                b.danmakuButton.text = "弹幕：开（${items.size}）· 长按导出"
            } catch (e: Exception) {
                b.danmakuButton.text = "弹幕加载失败"
                b.playerStatus.text = "播放中 · 弹幕暂不可用：${e.message}"
            }
        }
    }


    private fun exportDanmaku() {
        if (loadedDanmaku.isEmpty()) {
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
        (b.commentsList.adapter as CommentAdapter).submit(emptyList())
        loadComments(true)
    }

    private fun loadComments(reset: Boolean) {
        if (commentLoading || (!commentHasMore && !reset)) return
        commentLoading = true
        b.commentsStatus.text = if (reset) "正在加载评论…" else "正在加载更多评论…"
        lifecycleScope.launch {
            try {
                val page = commentPage
                val result = withContext(Dispatchers.IO) { BilimiaoApiBridge.commentsPage(aid, page, commentSort) }
                (b.commentsList.adapter as CommentAdapter).submit(result.comments, append = !reset)
                commentPage = page + 1
                commentHasMore = result.hasMore
                b.commentsStatus.text = "评论 · ${result.total} 条" + if (commentHasMore) " · 上滑加载更多" else " · 已加载完"
            } catch (e: Exception) {
                b.commentsStatus.text = "评论加载失败：${e.message ?: "网络异常"}"
            } finally { commentLoading = false }
        }
    }

    private fun showReplies(comment: VideoComment) {
        lifecycleScope.launch {
            try {
                val replies = withContext(Dispatchers.IO) { BilimiaoApiBridge.replies(aid, comment.id, 1) }
                val lines = replies.map { "${it.user}：${it.text}" }.toTypedArray()
                MaterialAlertDialogBuilder(this@VideoPlayerActivity)
                    .setTitle("评论回复 · ${comment.replyCount}")
                    .setItems(lines, null)
                    .setPositiveButton("关闭", null)
                    .show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@VideoPlayerActivity).setMessage("回复加载失败：${e.message}").setPositiveButton("知道了", null).show()
            }
        }
    }

    private fun likeComment(comment: VideoComment) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { BilimiaoApiBridge.likeComment(aid, comment.id, !comment.liked) }
                (b.commentsList.adapter as CommentAdapter).updateLike(comment.id, !comment.liked)
                b.commentsStatus.text = result
            } catch (e: Exception) { b.commentsStatus.text = e.message ?: "点赞失败" }
        }
    }

    private fun sendComment() {
        val text = b.commentInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) { b.commentsStatus.text = "先输入评论内容"; return }
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { BilimiaoApiBridge.sendComment(aid, text) }
                b.commentInput.text?.clear()
                b.commentsStatus.text = result
                resetComments()
            } catch (e: Exception) { b.commentsStatus.text = e.message ?: "发送失败" }
        }
    }


    private fun requireAid(): Long {
        if (aid <= 0L) error("当前视频缺少 AV 号，无法执行互动")
        return aid
    }

    private fun toggleVideoLike() {
        lifecycleScope.launch {
            try {
                val target = !videoLiked
                withContext(Dispatchers.IO) {
                    BilimiaoApiBridge.likeVideo(requireAid(), target)
                }
                videoLiked = target
                b.videoLike.text = if (target) "已点赞" else "点赞"
                b.playerStatus.text = if (target) "视频点赞成功" else "已取消点赞"
            } catch (e: Exception) {
                b.playerStatus.text = e.message ?: "视频点赞失败"
            }
        }
    }

    private fun showCoinDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("给视频投币")
            .setItems(arrayOf("投 1 枚硬币", "投 2 枚硬币", "投币并点赞")) { _, which ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BilimiaoApiBridge.coin(requireAid(), if (which == 1) 2 else 1, which == 2)
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

    private fun tripleAction() {
        MaterialAlertDialogBuilder(this)
            .setTitle("一键三连")
            .setMessage("将尝试执行 B站一键三连操作。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BilimiaoApiBridge.triple(requireAid())
                        }
                        videoLiked = true
                        b.videoLike.text = "已点赞"
                        b.playerStatus.text = "一键三连成功"
                    } catch (e: Exception) {
                        b.playerStatus.text = e.message ?: "一键三连失败"
                    }
                }
            }.show()
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
        b.playerView.player = null
        exo?.release()
        exo = null
        super.onDestroy()
    }
}
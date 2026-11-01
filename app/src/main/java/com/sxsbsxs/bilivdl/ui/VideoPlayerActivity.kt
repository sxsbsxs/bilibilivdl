package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sxsbsxs.bilivdl.data.BiliPlaybackApi
import com.sxsbsxs.bilivdl.data.BiliInteractionApi
import com.sxsbsxs.bilivdl.data.BiliSession
import com.sxsbsxs.bilivdl.data.BiliVideoActionApi
import com.sxsbsxs.bilivdl.data.BiliDownloadEngine
import com.sxsbsxs.bilivdl.data.PlaybackInfo
import com.sxsbsxs.bilivdl.data.VideoComment
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
    }
    private lateinit var b: ActivityVideoPlayerBinding
    private var exo: ExoPlayer? = null
    private var info: PlaybackInfo? = null
    private var danmakuEnabled = true
    private var commentPage = 1
    private var commentHasMore = true
    private var commentLoading = false
    private var commentSort = 3
    private var videoLiked = false
    private var playbackSpeedIndex = 0
    private val playbackSpeeds = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 0.75f)
    private val bvid by lazy { intent.getStringExtra(EXTRA_BVID).orEmpty() }
    private val aid by lazy { intent.getLongExtra(EXTRA_AID, 0L) }
    private val cookie by lazy { BiliSession.cookie(this) }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        b = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.playerTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        b.commentsList.layoutManager = LinearLayoutManager(this)
        b.commentsList.adapter = CommentAdapter(::showReplies, ::likeComment)
        b.qualityButton.setOnClickListener { showQuality() }
        b.playerDownload.setOnClickListener { showDownloadQuality() }
        b.playbackSpeedButton.setOnClickListener { cyclePlaybackSpeed() }
        b.danmakuButton.setOnClickListener {
            danmakuEnabled = !danmakuEnabled
            b.danmakuOverlay.setDanmakuEnabled(danmakuEnabled)
            b.danmakuButton.text = if (danmakuEnabled) "弹幕：开" else "弹幕：关"
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
        b.commentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                if (lm.findLastVisibleItemPosition() >= (rv.adapter?.itemCount ?: 0) - 4) loadComments(false)
            }
        })
        load(80)
        loadComments(true)
    }

    private fun load(qn: Int) {
        b.playerStatus.text = "正在获取播放地址…"
        lifecycleScope.launch {
            try {
                val p = withContext(Dispatchers.IO) { BiliPlaybackApi.load(bvid, aid, qn, cookie) }
                info = p
                exo?.release()
                val headers = mutableMapOf("Referer" to "https://www.bilibili.com/", "Origin" to "https://www.bilibili.com", "Accept" to "*/*")
                if (cookie.isNotBlank()) headers["Cookie"] = cookie
                val http = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    .setConnectTimeoutMs(20000).setReadTimeoutMs(25000)
                    .setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(headers)
                val source = DefaultMediaSourceFactory(this@VideoPlayerActivity).setDataSourceFactory(http)
                exo = ExoPlayer.Builder(this@VideoPlayerActivity).setMediaSourceFactory(source).build().also { player ->
                    b.playerView.player = player
                    player.setMediaItem(if (p.isDash) MediaItem.Builder().setUri(p.url).setMimeType(MimeTypes.APPLICATION_MPD).build() else MediaItem.fromUri(p.url))
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            b.playerStatus.text = "播放失败：${error.errorCodeName}。请检查网络或账号权限"
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY) b.playerStatus.text = "正在播放 · ${p.name}"
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
                    val position = exo?.currentPosition ?: 0L
                    load(p.qualities[i].first)
                    exo?.seekTo(position)
                }
            }.show()
    }

    private fun showDownloadQuality() {
        val p = info
        if (p == null) { b.downloadStatus.text = "请先等待播放地址加载完成"; return }
        val choices = if (p.qualities.isEmpty()) listOf(p.quality to p.name) else p.qualities
        MaterialAlertDialogBuilder(this).setTitle("选择下载清晰度")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, choices.map { it.second }.toTypedArray())) { _, i ->
                startDownload(choices[i].first)
            }.show()
    }

    private fun startDownload(qn: Int) {
        b.downloadStatus.text = "正在获取下载地址…"
        lifecycleScope.launch {
            val taskId = java.util.UUID.randomUUID().toString()
            val prefs = getSharedPreferences("download_queue", MODE_PRIVATE)
            var task: JSONObject = JSONObject()
            fun saveTask() {
                val old = JSONArray(prefs.getString("tasks", "[]"))
                val updated = JSONArray()
                for (i in 0 until old.length()) {
                    val item = old.optJSONObject(i) ?: continue
                    if (item.optString("id") != taskId) updated.put(item)
                }
                updated.put(task)
                prefs.edit().putString("tasks", updated.toString()).apply()
            }
            try {
                val p = withContext(Dispatchers.IO) { BiliPlaybackApi.load(bvid, aid, qn, cookie) }
                val title = b.playerTitle.text.toString().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "bilibili_video" }
                task = JSONObject().put("id", taskId).put("title", title).put("quality", p.name)
                    .put("status", "downloading").put("progress", "准备下载").put("time", System.currentTimeMillis())
                saveTask()
                b.downloadStatus.text = "正在下载并校验资源…"
                val result = withContext(Dispatchers.IO) {
                    BiliDownloadEngine.download(this@VideoPlayerActivity, p, title, cookie) { done: Long, total: Long, stage: String ->
                        val label = if (total > 0L) "$stage · ${done * 100 / total}%" else "$stage · ${done / 1024} KB"
                        task.put("progress", label)
                        saveTask()
                        runOnUiThread { b.downloadStatus.text = label }
                    }
                }
                task.put("path", result).put("status", "completed").put("progress", "下载完成")
                saveTask()
                b.downloadStatus.text = "下载完成 · Movies/BiliVdl/$title.mp4"
            } catch (e: Exception) {
                task.put("status", "failed").put("progress", e.message ?: "下载失败")
                saveTask()
                b.downloadStatus.text = "下载失败：${e.message ?: "接口异常"}"
            }
        }
    }

    private fun loadDanmaku(cid: Long) {
        lifecycleScope.launch {
            try {
                val items = withContext(Dispatchers.IO) { BiliInteractionApi.danmaku(cid, cookie) }
                b.danmakuOverlay.setDanmaku(items)
                b.danmakuOverlay.setPlaybackPositionProvider { exo?.currentPosition ?: 0L }
                b.danmakuOverlay.setDanmakuEnabled(danmakuEnabled)
                b.danmakuButton.text = "弹幕：开（${items.size}）"
            } catch (e: Exception) {
                b.danmakuButton.text = "弹幕加载失败"
                b.playerStatus.text = "播放中 · 弹幕暂不可用：${e.message}"
            }
        }
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
                val result = withContext(Dispatchers.IO) { BiliInteractionApi.commentsPage(aid, page, commentSort, cookie) }
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
                val replies = withContext(Dispatchers.IO) { BiliInteractionApi.replies(aid, comment.id, 1, cookie) }
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
                val result = withContext(Dispatchers.IO) { BiliInteractionApi.likeComment(aid, comment.id, !comment.liked, cookie) }
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
                val result = withContext(Dispatchers.IO) { BiliInteractionApi.sendComment(aid, text, cookie) }
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
                    BiliVideoActionApi.like(requireAid(), target, cookie)
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
                            BiliVideoActionApi.coin(requireAid(), if (which == 1) 2 else 1, which == 2, cookie)
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
                            BiliVideoActionApi.triple(requireAid(), cookie)
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

    override fun onStop() { exo?.pause(); super.onStop() }
    override fun onDestroy() {
        b.playerView.player = null
        exo?.release()
        exo = null
        super.onDestroy()
    }
}
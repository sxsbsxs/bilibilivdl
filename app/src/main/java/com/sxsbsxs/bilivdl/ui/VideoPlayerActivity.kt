package com.sxsbsxs.bilivdl.ui

import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
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
                    player.setMediaItem(MediaItem.fromUri(p.url))
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
            try {
                val p = withContext(Dispatchers.IO) { BiliPlaybackApi.load(bvid, aid, qn, cookie) }
                if (!p.url.startsWith("https://")) error("下载地址无效")
                val title = b.playerTitle.text.toString().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "bilibili_video" }
                val request = DownloadManager.Request(Uri.parse(p.url)).setTitle(title).setDescription("BiliVdl · ${p.name}")
                    .setMimeType("video/mp4").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .addRequestHeader("Referer", "https://www.bilibili.com/")
                    .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                if (cookie.isNotBlank()) request.addRequestHeader("Cookie", cookie)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "BiliVdl/$title.mp4")
                val id = (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                val prefs = getSharedPreferences("download_queue", MODE_PRIVATE)
                val tasks = JSONArray(prefs.getString("tasks", "[]"))
                tasks.put(JSONObject().put("id", id).put("title", title).put("quality", p.name))
                prefs.edit().putString("tasks", tasks.toString()).apply()
                b.downloadStatus.text = "已加入下载队列 · ${p.name}。可在底部“下载”查看进度"
            } catch (e: Exception) { b.downloadStatus.text = "下载失败：${e.message ?: "接口异常"}" }
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
                val result = withContext(Dispatchers.IO) { BiliInteractionApi.likeComment(aid, comment.id, true, cookie) }
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

    override fun onStop() { exo?.pause(); super.onStop() }
    override fun onDestroy() {
        b.playerView.player = null
        exo?.release()
        exo = null
        super.onDestroy()
    }
}
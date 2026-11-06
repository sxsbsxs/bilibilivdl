package com.sxsbsxs.bilivdl.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.sxsbsxs.bilivdl.data.queue.DownloadTaskEntity
import com.sxsbsxs.bilivdl.data.queue.DownloadQueueStore
import com.sxsbsxs.bilivdl.data.queue.DownloadQueueDatabase
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistent foreground download runner. It keeps downloads alive after the player
 * screen closes and asks Android to redeliver the active task after process recreation.
 */
class BiliDownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "bili_video_download"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_ID = "task_id"
        private const val EXTRA_BVID = "bvid"
        private const val EXTRA_AID = "aid"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_QN = "qn"
        private const val EXTRA_EP_ID = "episode_id"
        private const val EXTRA_PAGE_INDEX = "page_index"
        private const val EXTRA_VIDEO_CODEC = "video_codec"
        private const val EXTRA_AUDIO_CODEC = "audio_codec"
        private const val EXTRA_COVER = "cover"
        private const val EXTRA_AUTHOR = "author"
        private const val EXTRA_ACTION = "queue_action"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_CANCEL = "cancel"
        private val running = ConcurrentHashMap<String, Job>()
        private val requestedActions = ConcurrentHashMap<String, String>()
        // Keep a fast resume request while the cancelled worker is unwinding.
        private val pendingRestarts = ConcurrentHashMap<String, Intent>()
        private val downloadSlots = Semaphore(2)

        fun pause(context: Context, id: String) = sendControl(context, id, ACTION_PAUSE)
        fun cancel(context: Context, id: String) = sendControl(context, id, ACTION_CANCEL)
        fun resume(context: Context, id: String, bvid: String, aid: Long, title: String, qn: Int, episodeId: Long = 0L, videoCodec: String = "", audioCodec: String = "", pageIndex: Int = 0, cover: String = "", author: String = "") =
            enqueue(context, id, bvid, aid, title, qn, episodeId, videoCodec, audioCodec, pageIndex, cover, author)

        private fun sendControl(context: Context, id: String, action: String) {
            val intent = Intent(context, BiliDownloadService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_ACTION, action)
            ContextCompat.startForegroundService(context, intent)
        }

        fun enqueue(context: Context, id: String, bvid: String, aid: Long, title: String, qn: Int, episodeId: Long = 0L, videoCodec: String = "", audioCodec: String = "", pageIndex: Int = 0, cover: String = "", author: String = "") {
            val intent = Intent(context, BiliDownloadService::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_BVID, bvid)
                putExtra(EXTRA_AID, aid)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_QN, qn)
                putExtra(EXTRA_EP_ID, episodeId)
                putExtra(EXTRA_VIDEO_CODEC, videoCodec)
                putExtra(EXTRA_AUDIO_CODEC, audioCodec)
                putExtra(EXTRA_PAGE_INDEX, pageIndex)
                putExtra(EXTRA_COVER, cover)
                putExtra(EXTRA_AUTHOR, author)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
    private val latestStartId = AtomicInteger(0)
    private val taskPersistenceMutex = Mutex()
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示视频下载进度"
                    setShowBadge(false)
                }
            )
        }
        startForeground(NOTIFICATION_ID, notification("正在恢复下载队列", null, null))
        scope.launch {
            runCatching {
                DownloadQueueStore.migrateLegacy(this@BiliDownloadService)
                val dao = DownloadQueueDatabase.get(this@BiliDownloadService).tasks()
                dao.recoverInterrupted()
                dao.tasksByStatus("queued").forEach { task ->
                    val restore = Intent(this@BiliDownloadService, BiliDownloadService::class.java).apply {
                        putExtra(EXTRA_ID, task.taskId)
                        putExtra(EXTRA_BVID, task.bvid)
                        putExtra(EXTRA_AID, task.aid)
                        putExtra(EXTRA_TITLE, task.title)
                        putExtra(EXTRA_QN, task.qn)
                        putExtra(EXTRA_EP_ID, task.episodeId)
                        putExtra(EXTRA_VIDEO_CODEC, task.videoCodec)
                        putExtra(EXTRA_AUDIO_CODEC, task.audioCodec)
                        putExtra(EXTRA_PAGE_INDEX, task.pageIndex)
                        putExtra(EXTRA_COVER, task.cover)
                        putExtra(EXTRA_AUTHOR, task.author)
                    }
                    startService(restore)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        val taskId = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action == ACTION_PAUSE || action == ACTION_CANCEL) {
            // A later pause/cancel supersedes any queued resume request.
            pendingRestarts.remove(taskId)
            requestedActions[taskId] = action
            val job = running[taskId]
            if (job != null) {
                job.cancel(CancellationException(action))
            } else {
                updateStoredTaskState(taskId, if (action == ACTION_CANCEL) "cancelled" else "paused",
                    if (action == ACTION_CANCEL) "已取消" else "已暂停 · 可继续")
                if (running.isEmpty()) stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val aid = intent.getLongExtra(EXTRA_AID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val qn = intent.getIntExtra(EXTRA_QN, 127)
        val episodeId = intent.getLongExtra(EXTRA_EP_ID, 0L)
        val pageIndex = intent.getIntExtra(EXTRA_PAGE_INDEX, 0).coerceAtLeast(0)
        val previousTasks = JSONArray(getSharedPreferences("download_queue", MODE_PRIVATE).getString("tasks", "[]"))
        val previousTask = (0 until previousTasks.length()).mapNotNull { previousTasks.optJSONObject(it) }
            .firstOrNull { it.optString("id") == taskId }
        val cover = intent.getStringExtra(EXTRA_COVER).orEmpty().ifBlank { previousTask?.optString("cover").orEmpty() }
        val author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty().ifBlank { previousTask?.optString("author").orEmpty() }
        val savedOptions = getSharedPreferences("download_options", MODE_PRIVATE)
        val videoCodec = intent.getStringExtra(EXTRA_VIDEO_CODEC).orEmpty().ifBlank { savedOptions.getString(taskId + "_video", "").orEmpty() }
        val audioCodec = intent.getStringExtra(EXTRA_AUDIO_CODEC).orEmpty().ifBlank { savedOptions.getString(taskId + "_audio", "").orEmpty() }
        // Do not drop a fast resume while the previous cancelled coroutine is still unwinding.
        val existingJob = running[taskId]
        if (existingJob != null) {
            if (existingJob.isCancelled || !existingJob.isActive) {
                pendingRestarts[taskId] = Intent(intent)
            }
            return START_REDELIVER_INTENT
        }

        val taskJob = scope.launch(start = CoroutineStart.LAZY) {
            var slotAcquired = false
            val task = JSONObject()
                .put("id", taskId)
                .put("title", title)
                .put("quality", qnLabel(qn))
                .put("bvid", bvid)
                .put("aid", aid)
                .put("qn", qn)
                .put("episodeId", episodeId)
                .put("pageIndex", pageIndex)
                .put("cover", cover)
                .put("author", author)
                .put("videoCodec", videoCodec)
                .put("audioCodec", audioCodec)
                .put("status", "queued")
                .put("progress", "等待下载队列")
                .put("time", System.currentTimeMillis())
            try {
                updateTask(task)
                downloadSlots.acquire()
                slotAcquired = true
                task.put("status", "downloading").put("progress", "正在获取视频信息")
                updateTask(task)
                val cookie = BiliSession.cookie(this@BiliDownloadService)
                val accountProfile = if (cookie.isNotBlank()) {
                    runCatching { BiliSession.profile(this@BiliDownloadService, cookie) }.getOrNull()
                } else null
                val accountIsVip = (accountProfile?.optInt("vipType") ?: 0) > 0
                val accountIsLoggedIn = accountProfile?.optBoolean("isLogin") == true
                suspend fun resolvePlayback(): PlaybackInfo =
                    if (episodeId > 0L) BangumiApiBridge.playback(episodeId, qn, videoCodec, audioCodec)
                    else BiliPlaybackApi.load(bvid, aid, qn, cookie, pageIndex, videoCodec, audioCodec,
                        isVip = accountIsVip, isLoggedIn = accountIsLoggedIn)
                val playback = resolvePlayback()
                task.put("cid", playback.cid).put("quality", playback.name)
                task.put("progress", "准备下载")
                updateTask(task)
                val lastProgressWrite = AtomicLong(0L)
                val result = BiliDownloadEngine.download(
                    this@BiliDownloadService, playback, title, cookie, taskId,
                    refreshPlayback = { resolvePlayback() }
                ) { done, total, stage ->
                    val progress = if (total > 0L) "$stage · ${(done * 100 / total).coerceIn(0, 100)}%" else "$stage · ${done / 1024} KB"
                    // DASH video/audio callbacks run concurrently; JSONObject is not thread-safe.
                    // Serialize mutations and snapshots so concurrent chunk callbacks cannot corrupt task state.
                    val snapshot = synchronized(task) {
                        task.put("completedBytes", done).put("totalBytes", total).put("progress", progress)
                        val now = System.currentTimeMillis()
                        val previous = lastProgressWrite.get()
                        if (now - previous >= 1000L && lastProgressWrite.compareAndSet(previous, now)) {
                            JSONObject(task.toString())
                        } else null
                    }
                    if (snapshot != null) scope.launch { runCatching { updateProgress(snapshot) } }
                    notificationManager.notify(NOTIFICATION_ID, notification(title, done, total.takeIf { it > 0L }))
                }
                task.put("path", result)
                    .put("status", "completed")
                    .put("progress", "下载完成")
                updateTask(task)
                notificationManager.notify(NOTIFICATION_ID, notification("下载完成：$title", null, null))
            } catch (cancelled: CancellationException) {
                val requested = requestedActions.remove(taskId)
                task.put("status", if (requested == ACTION_CANCEL) "cancelled" else "paused")
                    .put("progress", if (requested == ACTION_CANCEL) "已取消" else "已暂停 · 可继续")
                if (requested == ACTION_CANCEL) {
                    BiliDownloadEngine.cleanupTask(this@BiliDownloadService, taskId, title)
                }
                withContext(NonCancellable) { updateTask(task) }
            } catch (error: Exception) {
                task.put("status", "failed")
                    .put("progress", error.message ?: "下载失败")
                updateTask(task)
                notificationManager.notify(NOTIFICATION_ID, notification("下载失败：$title", null, null))
            } finally {
                if (slotAcquired) downloadSlots.release()
                running.remove(taskId, this.coroutineContext[Job])
                val pendingRestart = pendingRestarts.remove(taskId)
                val remaining = active.decrementAndGet()
                if (pendingRestart != null) {
                    // Keep the foreground service alive across the hand-off.
                    startService(pendingRestart)
                } else if (remaining == 0) {
                    stopSelfResult(latestStartId.get())
                }
            }
        }
        val existing = running.putIfAbsent(taskId, taskJob)
        if (existing != null) return START_REDELIVER_INTENT
        active.incrementAndGet()
        taskJob.start()
        return START_REDELIVER_INTENT
    }

    private fun updateStoredTaskState(id: String, status: String, progress: String) {
        val prefs = getSharedPreferences("download_queue", MODE_PRIVATE)
        synchronized(BiliDownloadService::class.java) {
            val tasks = JSONArray(prefs.getString("tasks", "[]"))
            for (i in 0 until tasks.length()) {
                val item = tasks.optJSONObject(i) ?: continue
                if (item.optString("id") != id) continue
                item.put("status", status).put("progress", progress)
                if (status == "cancelled") {
                    BiliDownloadEngine.cleanupTask(this, id, item.optString("title"))
                }
                break
            }
            prefs.edit().putString("tasks", tasks.toString()).apply()
        }
        scope.launch { runCatching { DownloadQueueDatabase.get(this@BiliDownloadService).tasks().setState(id, status, progress) } }
    }

    private fun qnLabel(qn: Int): String = when (qn) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P 60帧"
        112 -> "1080P+"
        74 -> "720P 60帧"
        80 -> "1080P"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "清晰度 $qn"
    }

    private suspend fun updateTask(task: JSONObject) {
        taskPersistenceMutex.withLock { persistTask(task) }
    }

    /** Ignore delayed progress snapshots once a task has moved out of downloading state. */
    private suspend fun updateProgress(task: JSONObject) {
        taskPersistenceMutex.withLock {
            val id = task.optString("id")
            val prefs = getSharedPreferences("download_queue", MODE_PRIVATE)
            val stillDownloading = synchronized(BiliDownloadService::class.java) {
                val current = JSONArray(prefs.getString("tasks", "[]"))
                (0 until current.length()).any { index ->
                    val item = current.optJSONObject(index)
                    item != null && item.optString("id") == id && item.optString("status") == "downloading"
                }
            }
            if (stillDownloading) persistTask(task)
        }
    }

    private suspend fun persistTask(task: JSONObject) {
        val prefs = getSharedPreferences("download_queue", MODE_PRIVATE)
        synchronized(BiliDownloadService::class.java) {
            val old = JSONArray(prefs.getString("tasks", "[]"))
            val updated = JSONArray()
            var replaced = false
            for (i in 0 until old.length()) {
                val item = old.optJSONObject(i) ?: continue
                if (item.optString("id") == task.optString("id")) {
                    updated.put(task)
                    replaced = true
                } else updated.put(item)
            }
            if (!replaced) updated.put(task)
            prefs.edit().putString("tasks", updated.toString()).apply()
        }
        DownloadQueueStore.save(this, DownloadTaskEntity(
            taskId = task.optString("id"),
            title = task.optString("title", "视频"),
            bvid = task.optString("bvid"),
            aid = task.optLong("aid"),
            qn = task.optInt("qn", 80),
            episodeId = task.optLong("episodeId"),
            pageIndex = task.optInt("pageIndex", 0),
            cover = task.optString("cover"),
            author = task.optString("author"),
            quality = task.optString("quality"),
            status = task.optString("status", "queued"),
            progress = task.optString("progress"),
            outputPath = task.optString("path"),
            videoCodec = task.optString("videoCodec"),
            audioCodec = task.optString("audioCodec"),
            cid = task.optLong("cid"),
            completedBytes = task.optLong("completedBytes", 0L),
            totalBytes = task.optLong("totalBytes", -1L),
            createdAt = task.optLong("time", System.currentTimeMillis())
        ))
    }

    private fun notification(title: String, done: Long?, total: Long?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("BiliVdl 视频下载")
            .setContentText(title)
            .setOngoing(done != null)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (done != null && total != null && total > 0L) {
            builder.setProgress(100, (done * 100 / total).toInt().coerceIn(0, 100), false)
        } else if (done != null) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

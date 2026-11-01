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
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

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
        private const val EXTRA_ACTION = "queue_action"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_CANCEL = "cancel"
        private val running = ConcurrentHashMap<String, Job>()
        private val requestedActions = ConcurrentHashMap<String, String>()

        fun pause(context: Context, id: String) = sendControl(context, id, ACTION_PAUSE)
        fun cancel(context: Context, id: String) = sendControl(context, id, ACTION_CANCEL)
        fun resume(context: Context, id: String, bvid: String, aid: Long, title: String, qn: Int) =
            enqueue(context, id, bvid, aid, title, qn)

        private fun sendControl(context: Context, id: String, action: String) {
            val intent = Intent(context, BiliDownloadService::class.java)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_ACTION, action)
            ContextCompat.startForegroundService(context, intent)
        }

        fun enqueue(context: Context, id: String, bvid: String, aid: Long, title: String, qn: Int) {
            val intent = Intent(context, BiliDownloadService::class.java).apply {
                putExtra(EXTRA_ID, id)
                putExtra(EXTRA_BVID, bvid)
                putExtra(EXTRA_AID, aid)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_QN, qn)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicInteger(0)
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
        startForeground(NOTIFICATION_ID, notification("下载任务准备中", null, null))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action == ACTION_PAUSE || action == ACTION_CANCEL) {
            requestedActions[taskId] = action
            val job = running[taskId]
            job?.cancel(CancellationException(action))
            if (job == null && running.isEmpty()) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val aid = intent.getLongExtra(EXTRA_AID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val qn = intent.getIntExtra(EXTRA_QN, 80)
        active.incrementAndGet()

        val taskJob = scope.launch {
            val task = JSONObject()
                .put("id", taskId)
                .put("title", title)
                .put("quality", qnLabel(qn))
                .put("bvid", bvid)
                .put("aid", aid)
                .put("qn", qn)
                .put("status", "downloading")
                .put("progress", "正在获取视频信息")
                .put("time", System.currentTimeMillis())
            updateTask(task)
            try {
                val cookie = BiliSession.cookie(this@BiliDownloadService)
                val playback = BiliPlaybackApi.load(bvid, aid, qn, cookie)
                task.put("quality", playback.name)
                task.put("progress", "准备下载")
                updateTask(task)
                val result = BiliDownloadEngine.download(this@BiliDownloadService, playback, title, cookie) { done, total, stage ->
                    val progress = if (total > 0L) "$stage · ${(done * 100 / total).coerceIn(0, 100)}%" else "$stage · ${done / 1024} KB"
                    task.put("progress", progress)
                    updateTask(task)
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
                    val dir = java.io.File(cacheDir, "bili-download")
                    val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "bilibili_video" }
                    dir.listFiles()?.filter { it.name.startsWith(safe) }?.forEach { it.delete() }
                }
                updateTask(task)
            } catch (error: Exception) {
                task.put("status", "failed")
                    .put("progress", error.message ?: "下载失败")
                updateTask(task)
                notificationManager.notify(NOTIFICATION_ID, notification("下载失败：$title", null, null))
            } finally {
                running.remove(taskId)
                if (active.decrementAndGet() == 0) {
                    stopSelf()
                }
            }
        }
        running[taskId] = taskJob
        return START_REDELIVER_INTENT
    }

    private fun qnLabel(qn: Int): String = when (qn) {
        127 -> "8K"
        126 -> "杜比视界"
        125 -> "HDR"
        120 -> "4K"
        116 -> "1080P 60帧"
        112 -> "1080P+"
        80 -> "1080P"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "清晰度 $qn"
    }

    private fun updateTask(task: JSONObject) {
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

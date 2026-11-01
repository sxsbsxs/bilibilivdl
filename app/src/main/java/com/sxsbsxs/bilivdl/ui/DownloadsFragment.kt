package com.sxsbsxs.bilivdl.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliApi
import com.sxsbsxs.bilivdl.data.BiliPlaybackApi
import com.sxsbsxs.bilivdl.data.BiliVideoLookup
import com.sxsbsxs.bilivdl.databinding.FragmentDownloadBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class DownloadsFragment : Fragment(R.layout.fragment_download) {
    private var _binding: FragmentDownloadBinding? = null
    private val b get() = _binding!!
    private data class Task(val id: Long, val title: String)
    private val prefs by lazy { requireContext().getSharedPreferences("download_queue", Context.MODE_PRIVATE) }
    private val manager by lazy { requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDownloadBinding.bind(view)
        b.downloadStart.setOnClickListener { startDownload() }
        refreshTasks()
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) { delay(1000); if (_binding != null) refreshTasks() }
        }
    }

    private fun startDownload() {
        val raw = b.downloadInput.text?.toString()?.trim().orEmpty()
        val id = BiliApi.resolveVideoId(raw) ?: run {
            b.downloadInput.error = "请输入有效链接、BV号或AV号"
            return
        }
        b.downloadInput.error = null
        b.downloadStart.isEnabled = false
        b.downloadStatus.text = "正在获取视频信息和可用清晰度…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val video = withContext(Dispatchers.IO) { BiliVideoLookup.get(id) }
                val playback = withContext(Dispatchers.IO) { BiliPlaybackApi.load(video.bvid, video.aid, 80) }
                val qualities = playback.qualities
                if (qualities.isEmpty()) enqueue(video.title, playback.url, playback.name)
                else {
                    val labels = qualities.map { it.second }.toTypedArray()
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("选择下载清晰度")
                        .setItems(labels) { _, which ->
                            val qn = qualities[which].first
                            viewLifecycleOwner.lifecycleScope.launch { fetchAndEnqueue(video.title, video.bvid, video.aid, qn) }
                        }
                        .setOnCancelListener { b.downloadStatus.text = "已取消选择清晰度" }
                        .show()
                }
                b.downloadStatus.text = "视频信息已获取，请选择清晰度"
            } catch (e: Exception) {
                b.downloadStatus.text = "获取失败：" + (e.message ?: "网络异常；部分视频需要登录")
            } finally { b.downloadStart.isEnabled = true }
        }
    }

    private suspend fun fetchAndEnqueue(title: String, bvid: String, aid: Long, qn: Int) {
        b.downloadStatus.text = "正在准备下载地址…"
        try {
            val p = withContext(Dispatchers.IO) { BiliPlaybackApi.load(bvid, aid, qn) }
            enqueue(title, p.url, p.name)
        } catch (e: Exception) { b.downloadStatus.text = "下载地址获取失败：" + (e.message ?: "接口异常") }
    }

    private fun enqueue(title: String, url: String, quality: String) {
        if (!url.startsWith("https://")) { b.downloadStatus.text = "下载地址无效，已取消任务"; return }
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "bilibili_video" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("BiliVdl · $quality")
            .setMimeType("video/mp4")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("Referer", "https://www.bilibili.com/")
            .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "BiliVdl/$safe.mp4")
        val downloadId = manager.enqueue(request)
        val arr = JSONArray(prefs.getString("tasks", "[]"))
        arr.put(JSONObject().put("id", downloadId).put("title", title).put("quality", quality))
        prefs.edit().putString("tasks", arr.toString()).apply()
        b.downloadStatus.text = "已加入系统下载队列；完成后可在 Movies/BiliVdl 找到文件"
        refreshTasks()
    }

    private fun refreshTasks() {
        val arr = JSONArray(prefs.getString("tasks", "[]"))
        if (arr.length() == 0) { b.downloadTasks.text = "还没有下载任务"; return }
        val lines = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optLong("id")
            val title = item.optString("title", "视频")
            val quality = item.optString("quality", "")
            val query = DownloadManager.Query().setFilterById(id)
            val cursor = manager.query(query)
            cursor.use {
                if (it == null || !it.moveToFirst()) { lines.add("• $title\n  任务记录不存在"); return@use }
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val soFar = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val text = when (status) {
                    DownloadManager.STATUS_PENDING -> "等待下载"
                    DownloadManager.STATUS_RUNNING -> if (total > 0) "下载中 · ${(soFar * 100 / total).toInt()}%" else "正在下载"
                    DownloadManager.STATUS_PAUSED -> "已暂停"
                    DownloadManager.STATUS_SUCCESSFUL -> "已完成 · Movies/BiliVdl"
                    DownloadManager.STATUS_FAILED -> "失败 · 错误码 ${it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}"
                    else -> "处理中"
                }
                lines.add("• $title（$quality）\n  $text")
            }
        }
        b.downloadTasks.text = lines.joinToString("\n\n")
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
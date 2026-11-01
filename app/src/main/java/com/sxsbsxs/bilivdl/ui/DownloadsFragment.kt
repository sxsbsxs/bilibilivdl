package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.databinding.FragmentDownloadBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

class DownloadsFragment : Fragment(R.layout.fragment_download) {
    private var _binding: FragmentDownloadBinding? = null
    private val b get() = _binding!!
    private val prefs by lazy { requireContext().getSharedPreferences("download_queue", Context.MODE_PRIVATE) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDownloadBinding.bind(view)
        b.clearCompleted.setOnClickListener { removeTasks(completedOnly = true) }
        b.clearHistory.setOnClickListener { removeTasks(completedOnly = false) }
        refreshTasks()
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (_binding != null) refreshTasks()
            }
        }
    }

    private fun refreshTasks() {
        val arr = JSONArray(prefs.getString("tasks", "[]"))
        if (arr.length() == 0) {
            b.downloadSummary.text = "共 0 个任务"
            b.downloadTasks.text = "还没有下载任务\n打开任意视频，在播放页点击“下载”即可添加任务"
            return
        }
        var downloading = 0
        var completed = 0
        var failed = 0
        val lines = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title", "视频")
            val quality = item.optString("quality", "")
            val status = item.optString("status", "unknown")
            val progress = item.optString("progress", "")
            when (status) {
                "downloading" -> downloading++
                "completed" -> completed++
                "failed" -> failed++
            }
            val label = when (status) {
                "downloading" -> "正在下载 · $progress"
                "completed" -> "已完成 · Movies/BiliVdl"
                "failed" -> "下载失败 · $progress"
                else -> progress.ifBlank { "等待处理" }
            }
            lines.add("• $title（$quality）\n  $label")
        }
        b.downloadSummary.text = "共 ${arr.length()} 个任务 · 下载中 $downloading · 已完成 $completed · 失败 $failed"
        b.downloadTasks.text = lines.asReversed().joinToString("\n\n")
    }

    private fun removeTasks(completedOnly: Boolean) {
        val old = JSONArray(prefs.getString("tasks", "[]"))
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            val status = item.optString("status")
            val remove = if (completedOnly) status == "completed" else status != "downloading"
            if (!remove) kept.put(item)
        }
        prefs.edit().putString("tasks", kept.toString()).apply()
        refreshTasks()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

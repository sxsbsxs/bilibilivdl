package com.sxsbsxs.bilivdl.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.data.BiliDownloadService
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
        b.retryFailed.setOnClickListener { retryFailedTasks() }
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
        b.downloadTaskControls.removeAllViews()
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
            addTaskControls(item, title, status)
        }
        b.downloadSummary.text = "共 ${arr.length()} 个任务 · 下载中 $downloading · 已完成 $completed · 失败 $failed"
        b.downloadTasks.text = lines.asReversed().joinToString("\n\n")
    }

    private fun addTaskControls(item: org.json.JSONObject, title: String, status: String) {
        val context = requireContext()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            background = context.getDrawable(com.sxsbsxs.bilivdl.R.drawable.bg_empty_card)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8
            }
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 14f
            setTextColor(context.getColor(com.sxsbsxs.bilivdl.R.color.text_primary))
        })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, click: () -> Unit) {
            actions.addView(MaterialButton(context).apply {
                text = label
                isAllCaps = false
                setOnClickListener { click(); refreshTasks() }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 6
                }
            })
        }
        val id = item.optString("id")
        when (status) {
            "downloading" -> action("暂停") { BiliDownloadService.pause(context, id) }
            "paused", "failed" -> action("继续") {
                BiliDownloadService.resume(context, id, item.optString("bvid"), item.optLong("aid"), title, item.optInt("qn", 80))
            }
        }
        if (status == "downloading" || status == "paused" || status == "failed") {
            action("取消") { BiliDownloadService.cancel(context, id) }
        }
        row.addView(actions)
        b.downloadTaskControls.addView(row)
    }

    private fun retryFailedTasks() {
        val arr = JSONArray(prefs.getString("tasks", "[]"))
        var count = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("status") != "failed") continue
            val id = item.optString("id")
            val bvid = item.optString("bvid")
            val aid = item.optLong("aid")
            val qn = item.optInt("qn", 80)
            if (id.isBlank() || (bvid.isBlank() && aid <= 0L)) continue
            com.sxsbsxs.bilivdl.data.BiliDownloadService.enqueue(
                requireContext(), id, bvid, aid, item.optString("title", "视频"), qn
            )
            count++
        }
        if (count == 0) {
            b.downloadSummary.text = "没有可重试的失败任务（旧任务可能缺少重试信息）"
        } else {
            b.downloadSummary.text = "已重新加入 $count 个失败任务"
        }
        refreshTasks()
    }

    private fun removeTasks(completedOnly: Boolean) {
        val old = JSONArray(prefs.getString("tasks", "[]"))
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            val status = item.optString("status")
            val remove = if (completedOnly) status == "completed" else status in setOf("completed", "failed", "cancelled")
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

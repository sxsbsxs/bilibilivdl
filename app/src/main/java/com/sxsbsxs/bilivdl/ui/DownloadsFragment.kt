package com.sxsbsxs.bilivdl.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliDownloadService
import com.sxsbsxs.bilivdl.data.queue.DownloadQueueDatabase
import com.sxsbsxs.bilivdl.data.queue.DownloadTaskEntity
import com.sxsbsxs.bilivdl.databinding.FragmentDownloadBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment(R.layout.fragment_download) {
 private var _binding: FragmentDownloadBinding? = null
 private val b get() = _binding!!
 private val dao by lazy { DownloadQueueDatabase.get(requireContext()).tasks() }

 override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
  super.onViewCreated(view, savedInstanceState)
  _binding = FragmentDownloadBinding.bind(view)
  b.clearCompleted.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { dao.clearCompleted() } }
  b.retryFailed.setOnClickListener { retryFailedTasks() }
  b.clearHistory.setOnClickListener { viewLifecycleOwner.lifecycleScope.launch { dao.clearHistory() } }
  viewLifecycleOwner.lifecycleScope.launch {
   dao.observeAll().collect { tasks -> if (_binding != null) refreshTasks(tasks) }
  }
 }

 private fun refreshTasks(tasks: List<DownloadTaskEntity>) {
  b.downloadTaskControls.removeAllViews()
  if (tasks.isEmpty()) {
   b.downloadSummary.text = "共 0 个任务"
   b.downloadTasks.text = "还没有下载任务\n打开任意视频，在播放页点击“下载”即可添加任务"
   return
  }
  val downloading = tasks.count { it.status == "downloading" }
  val completed = tasks.count { it.status == "completed" }
  val failed = tasks.count { it.status == "failed" }
  b.downloadSummary.text = "共 ${tasks.size} 个任务 · 下载中 $downloading · 已完成 $completed · 失败 $failed"
  b.downloadTasks.text = tasks.joinToString("\n\n") { task ->
   val state = when (task.status) {
    "downloading" -> "正在下载 · ${task.progress}"
    "completed" -> "已完成 · ${task.outputPath.ifBlank { "Movies/BiliVdl" }}"
    "failed" -> "下载失败 · ${task.progress}"
    "paused" -> "已暂停 · ${task.progress}"
    "cancelled" -> "已取消"
    else -> task.progress.ifBlank { "等待处理" }
   }
   "• ${task.title}（${task.quality}）\n  $state"
  }
  tasks.forEach { addTaskControls(it) }
 }

 private fun addTaskControls(task: DownloadTaskEntity) {
  val context = requireContext()
  val row = LinearLayout(context).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(14, 10, 14, 10)
   background = context.getDrawable(R.drawable.bg_empty_card)
   layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 }
  }
  row.addView(TextView(context).apply { text = task.title; textSize = 14f; setTextColor(context.getColor(R.color.text_primary)) })
  val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
  fun action(label: String, click: () -> Unit) {
   actions.addView(MaterialButton(context).apply {
    text = label; isAllCaps = false
    setOnClickListener { click() }
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 6 }
   })
  }
  when (task.status) {
   "downloading" -> action("暂停") { BiliDownloadService.pause(context, task.taskId) }
   "paused", "failed", "queued" -> action("继续") { BiliDownloadService.resume(context, task.taskId, task.bvid, task.aid, task.title, task.qn) }
  }
  if (task.status in setOf("downloading", "paused", "failed", "queued")) action("取消") { BiliDownloadService.cancel(context, task.taskId) }
  row.addView(actions)
  b.downloadTaskControls.addView(row)
 }

 private fun retryFailedTasks() {
  viewLifecycleOwner.lifecycleScope.launch {
   val tasks = dao.tasksByStatus("failed")
   tasks.forEach { task ->
    if (task.bvid.isNotBlank() || task.aid > 0L) {
     BiliDownloadService.enqueue(requireContext(), task.taskId, task.bvid, task.aid, task.title, task.qn)
    }
   }
   b.downloadSummary.text = if (tasks.isEmpty()) "没有可重试的失败任务" else "已重新加入 ${tasks.size} 个失败任务"
  }
 }

 override fun onDestroyView() { _binding = null; super.onDestroyView() }
}

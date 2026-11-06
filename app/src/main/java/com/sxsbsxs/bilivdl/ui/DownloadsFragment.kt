package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliDownloadService
import com.sxsbsxs.bilivdl.data.queue.DownloadQueueDatabase
import com.sxsbsxs.bilivdl.data.queue.DownloadTaskEntity
import com.sxsbsxs.bilivdl.databinding.FragmentDownloadBinding
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment(R.layout.fragment_download) {
    private var _binding: FragmentDownloadBinding? = null
    private val b get() = _binding!!
    private val dao by lazy { DownloadQueueDatabase.get(requireContext()).tasks() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDownloadBinding.bind(view)
        b.clearCompleted.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle("清理已完成")
                .setMessage("只清除下载记录，不会删除已保存到系统视频库的文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清理") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch { dao.clearCompleted() }
                }.show()
        }
        b.retryFailed.setOnClickListener { retryFailedTasks() }
        b.clearHistory.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle("清空下载历史")
                .setMessage("清除已完成、失败和已取消的任务记录，不会删除已下载的视频文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch { dao.clearHistory() }
                }.show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            dao.observeAll().collect { tasks -> if (_binding != null) refreshTasks(tasks) }
        }
    }

    private fun refreshTasks(tasks: List<DownloadTaskEntity>) {
        b.downloadTaskControls.removeAllViews()
        if (tasks.isEmpty()) {
            b.downloadSummary.text = ""
            b.downloadTasks.visibility = View.VISIBLE
            b.downloadTasks.text = "暂无下载任务"
            return
        }
        val active = tasks.count { it.status == "queued" || it.status == "downloading" }
        val paused = tasks.count { it.status == "paused" }
        val completed = tasks.count { it.status == "completed" }
        val failed = tasks.count { it.status == "failed" }
        b.downloadSummary.text = "全部 " + tasks.size + "    进行中 " + active + "    已暂停 " + paused + "    已完成 " + completed + "    失败 " + failed
        b.downloadTasks.visibility = View.GONE
        addBatchControls(tasks)
        tasks.forEach { addTaskCard(it) }
    }

    /** 批量操作直接作用于持久化任务队列，不只是界面按钮。 */
    private fun addBatchControls(tasks: List<DownloadTaskEntity>) {
        val context = requireContext()
        val active = tasks.filter { it.status == "downloading" || it.status == "queued" }
        val resumable = tasks.filter { it.status == "paused" || it.status == "failed" || it.status == "queued" }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        fun button(label: String, enabled: Boolean, action: () -> Unit) {
            controls.addView(MaterialButton(context).apply {
                text = label
                isAllCaps = false
                isEnabled = enabled
                minHeight = dp(36)
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                strokeWidth = 0
                elevation = 0f
                setTextColor(context.getColor(R.color.text_secondary))
                textSize = 12f
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginEnd = dp(5)
                }
            })
        }
        button("全部暂停", active.isNotEmpty()) {
            active.forEach {
                BiliDownloadService.pause(context, it.taskId)
            }
        }
        button("继续全部", resumable.isNotEmpty()) {
            resumable.forEach {
                BiliDownloadService.resume(context, it.taskId, it.bvid, it.aid,
                    it.title, it.qn, it.episodeId, it.videoCodec, it.audioCodec, it.pageIndex)
            }
        }
        button("取消全部", active.isNotEmpty()) {
            AlertDialog.Builder(context).setTitle("取消全部活动任务")
                .setMessage("将取消所有正在下载或排队中的任务，已完成的文件不会删除。")
                .setNegativeButton("返回", null)
                .setPositiveButton("全部取消") { _, _ ->
                    active.forEach { BiliDownloadService.cancel(context, it.taskId) }
                }.show()
        }
        b.downloadTaskControls.addView(controls)
    }

    private fun addTaskCard(task: DownloadTaskEntity) {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = context.getDrawable(R.drawable.bg_empty_card)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = context.getDrawable(R.drawable.bg_empty_card)
            contentDescription = "下载视频封面"
        }
        header.addView(cover, LinearLayout.LayoutParams(dp(116), dp(76)))
        if (task.cover.isNotBlank()) {
            val imageUrl = task.cover.let { if (it.startsWith("//")) "https:$it" else it }
            val headers = LazyHeaders.Builder()
                .addHeader("Referer", "https://www.bilibili.com/")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .build()
            Glide.with(cover).load(GlideUrl(imageUrl, headers))
                .placeholder(R.drawable.bg_empty_card).error(R.drawable.bg_empty_card)
                .centerCrop().into(cover)
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        details.addView(TextView(context).apply {
            text = task.title
            textSize = 14f
            includeFontPadding = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(context.getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        details.addView(TextView(context).apply {
            text = listOf(task.author.ifBlank { "哔哩哔哩视频" }, task.quality.ifBlank { "视频" }).joinToString(" · ")
            textSize = 12f
            maxLines = 1
            setPadding(0, dp(5), 0, 0)
            setTextColor(context.getColor(R.color.text_secondary))
        })
        header.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(header)
        card.addView(TextView(context).apply {
            text = statusText(task)
            textSize = 12f
            includeFontPadding = false
            setPadding(0, dp(8), 0, dp(7))
            setTextColor(context.getColor(R.color.text_secondary))
        })
        if (task.status == "downloading" || task.status == "queued") {
            card.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = task.totalBytes <= 0L
                if (!isIndeterminate) {
                    max = 1000
                    progress = ((task.completedBytes.toDouble() / task.totalBytes).coerceIn(0.0, 1.0) * 1000).toInt()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun action(label: String, click: () -> Unit) {
            actions.addView(MaterialButton(context).apply {
                text = label
                isAllCaps = false
                minHeight = dp(38)
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                strokeWidth = 0
                elevation = 0f
                setTextColor(context.getColor(R.color.brand))
                textSize = 12f
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
                ).apply { marginStart = dp(6) }
            })
        }
        when (task.status) {
            "downloading" -> action("暂停") { BiliDownloadService.pause(context, task.taskId) }
            "paused", "failed", "queued" -> action(if (task.status == "failed") "重试" else "继续") {
                BiliDownloadService.resume(context, task.taskId, task.bvid, task.aid, task.title, task.qn, task.episodeId, task.videoCodec, task.audioCodec, task.pageIndex)
            }
        }
        if (task.status == "downloading" || task.status == "paused" || task.status == "failed" || task.status == "queued") {
            action("取消") { BiliDownloadService.cancel(context, task.taskId) }
        }
        if (task.status == "completed" && task.outputPath.isNotBlank()) {
            action("文件详情") { openResource(task) }
            card.setOnClickListener { openResource(task) }
        } else {
            card.setOnClickListener { if (task.status == "failed") showFailure(task) }
        }
        card.addView(actions)
        b.downloadTaskControls.addView(card)
    }

    private fun openResource(task: DownloadTaskEntity) {
        startActivity(Intent(requireContext(), DownloadedResourceActivity::class.java).apply {
            putExtra(DownloadedResourceActivity.EXTRA_URI, task.outputPath)
            putExtra(DownloadedResourceActivity.EXTRA_TITLE, task.title)
            putExtra(DownloadedResourceActivity.EXTRA_QUALITY, task.quality)
        })
    }

    private fun showFailure(task: DownloadTaskEntity) {
        AlertDialog.Builder(requireContext()).setTitle("下载失败")
            .setMessage(task.progress.ifBlank { "下载失败，未收到详细错误信息。" })
            .setPositiveButton("知道了", null).show()
    }

    private fun retryFailedTasks() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tasks = dao.tasksByStatus("failed")
            tasks.forEach { task ->
                if (task.bvid.isNotBlank() || task.aid > 0L || task.episodeId > 0L) {
                    BiliDownloadService.enqueue(requireContext(), task.taskId, task.bvid, task.aid,
                        task.title, task.qn, task.episodeId, task.videoCodec, task.audioCodec, task.pageIndex)
                }
            }
            b.downloadSummary.text = if (tasks.isEmpty()) "没有可重试的失败任务" else "已重新加入 " + tasks.size + " 个失败任务"
        }
    }

    private fun statusText(task: DownloadTaskEntity): String = when (task.status) {
        "downloading" -> task.progress.ifBlank { "正在下载" }
        "completed" -> "已完成 · 点击查看文件"
        "failed" -> "失败 · " + task.progress
        "paused" -> "已暂停"
        "cancelled" -> "已取消"
        else -> task.progress.ifBlank { "等待下载" }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

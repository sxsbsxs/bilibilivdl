package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadsFragment : Fragment(R.layout.fragment_download) {
    private var _binding: FragmentDownloadBinding? = null
    private val b get() = _binding!!
    private val dao by lazy { DownloadQueueDatabase.get(requireContext()).tasks() }
    private var currentTasks: List<DownloadTaskEntity> = emptyList()
    private var taskFilter: String = "all"
    private data class SpeedSample(val bytes: Long, val atMs: Long)
    private val speedSamples = mutableMapOf<String, SpeedSample>()
    private val currentSpeeds = mutableMapOf<String, Long>()

    private val exportDirectoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            exportCompletedFiles(uri)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDownloadBinding.bind(view)
        b.localFiles.setOnClickListener {
            startActivity(Intent(requireContext(), LocalDownloadsActivity::class.java))
        }
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
        val previousScrollY = _binding?.downloadScroll?.scrollY ?: 0
        currentTasks = tasks
        updateSpeedSamples(tasks)
        b.downloadTaskControls.removeAllViews()
        if (tasks.isEmpty()) {
            b.downloadSummary.text = ""
            b.downloadTasks.visibility = View.VISIBLE
            b.downloadTasks.text = "暂无下载任务"
            restoreDownloadScroll(previousScrollY)
            return
        }
        val active = tasks.count { it.status == "queued" || it.status == "downloading" }
        val paused = tasks.count { it.status == "paused" }
        val completed = tasks.count { it.status == "completed" }
        val failed = tasks.count { it.status == "failed" }
        b.downloadSummary.text = "全部 " + tasks.size + "    进行中 " + active + "    已暂停 " + paused + "    已完成 " + completed + "    失败 " + failed
        b.downloadTasks.visibility = View.GONE
        addTaskFilters(tasks)
        val visible = when (taskFilter) {
            "active" -> tasks.filter { it.status == "queued" || it.status == "downloading" }
            "paused" -> tasks.filter { it.status == "paused" }
            "completed" -> tasks.filter { it.status == "completed" }
            "failed" -> tasks.filter { it.status == "failed" || it.status == "cancelled" }
            else -> tasks
        }
        addBatchControls(visible)
        if (visible.isEmpty()) {
            b.downloadTaskControls.addView(TextView(requireContext()).apply {
                text = "这个分类下暂时没有任务"
                textSize = 13f
                setTextColor(requireContext().getColor(R.color.text_secondary))
                setPadding(dp(8), dp(20), dp(8), dp(20))
            })
        } else visible.forEach { addTaskCard(it) }
        restoreDownloadScroll(previousScrollY)
    }

    private fun restoreDownloadScroll(previousScrollY: Int) {
        _binding?.downloadTaskControls?.post {
            _binding?.downloadScroll?.let { scroll ->
                val contentHeight = scroll.getChildAt(0)?.height ?: 0
                val maxScroll = (contentHeight - scroll.height).coerceAtLeast(0)
                scroll.scrollTo(0, previousScrollY.coerceIn(0, maxScroll))
            }
        }
    }

    private fun addTaskFilters(tasks: List<DownloadTaskEntity>) {
        val counts = mapOf(
            "all" to tasks.size,
            "active" to tasks.count { it.status == "queued" || it.status == "downloading" },
            "paused" to tasks.count { it.status == "paused" },
            "completed" to tasks.count { it.status == "completed" },
            "failed" to tasks.count { it.status == "failed" || it.status == "cancelled" }
        )
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val labels = listOf("all" to "全部", "active" to "进行中", "paused" to "已暂停", "completed" to "已完成", "failed" to "失败")
        labels.forEach { (key, label) ->
            row.addView(TextView(requireContext()).apply {
                text = "$label ${counts[key] ?: 0}"
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(7), dp(8), dp(7))
                setTextColor(requireContext().getColor(if (taskFilter == key) R.color.brand_dark else R.color.text_secondary))
                background = requireContext().getDrawable(if (taskFilter == key) R.drawable.bg_pill_soft else R.drawable.bg_surface_card)
                setOnClickListener {
                    taskFilter = key
                    refreshTasks(currentTasks)
                }
            }, LinearLayout.LayoutParams(0, dp(34), 1f))
        }
        b.downloadTaskControls.addView(row)
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
            controls.addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                minHeight = dp(34)
                includeFontPadding = false
                isEnabled = enabled
                val emphasized = enabled && label == "继续全部"
                setTextColor(context.getColor(if (emphasized) R.color.brand_dark else R.color.text_secondary))
                if (!enabled) alpha = 0.45f
                textSize = 11f
                setPadding(dp(4), 0, dp(4), 0)
                background = context.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { if (isEnabled) action() }
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    marginEnd = dp(3)
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
                    it.title, it.qn, it.episodeId, it.videoCodec, it.audioCodec, it.pageIndex, it.cover, it.author)
            }
        }
        val completed = tasks.filter { it.status == "completed" && it.outputPath.isNotBlank() }
        button("导出全部", completed.isNotEmpty()) {
            exportDirectoryPicker.launch(null)
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

        val removable = tasks.filter { it.status == "failed" || it.status == "cancelled" }
        if (removable.isNotEmpty()) {
            val cleanup = MaterialButton(context).apply {
                text = "移除失败记录（${removable.size}）"
                isAllCaps = false
                minHeight = dp(34)
                textSize = 12f
                elevation = 0f
                strokeWidth = dp(1)
                strokeColor = android.content.res.ColorStateList.valueOf(context.getColor(R.color.stroke_dark))
                backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.surface_dark))
                setTextColor(context.getColor(R.color.text_secondary))
                setOnClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("移除失败记录")
                        .setMessage("只移除失败和已取消的任务记录，不会删除已下载文件。未完成的临时文件会保留。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("移除") { _, _ ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                removable.forEach { dao.delete(it.taskId) }
                            }
                        }.show()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
                ).apply {
                    bottomMargin = dp(8)
                }
            }
            b.downloadTaskControls.addView(cleanup)
        }
    }

    private fun addTaskCard(task: DownloadTaskEntity) {
        val context = requireContext()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = context.getDrawable(R.drawable.bg_surface_card)
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
            setPadding(0, dp(8), 0, dp(4))
            setTextColor(context.getColor(R.color.text_secondary))
        })
        if (task.status == "downloading") {
            val speed = currentSpeeds[task.taskId] ?: 0L
            val remaining = if (speed > 0L && task.totalBytes > task.completedBytes)
                formatEtaSeconds((task.totalBytes - task.completedBytes) / speed) else ""
            card.addView(TextView(context).apply {
                text = "下载速度  " + formatBytesPerSecond(speed) + if (remaining.isNotBlank()) "    预计剩余 " + remaining else ""
                textSize = 11f
                setPadding(0, 0, 0, dp(7))
                setTextColor(context.getColor(R.color.brand_dark))
            })
        }
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
        fun action(label: String, primary: Boolean = false, click: () -> Unit) {
            actions.addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                minHeight = dp(32)
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                setTextColor(context.getColor(if (primary) R.color.brand_dark else R.color.text_secondary))
                if (primary) setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 12f
                setPadding(dp(10), 0, dp(10), 0)
                background = context.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { click() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { marginStart = dp(3) }
            })
        }
        when (task.status) {
            "downloading" -> action("暂停", primary = true) { BiliDownloadService.pause(context, task.taskId) }
            "paused", "failed", "queued" -> action(if (task.status == "failed") "重试" else "继续", primary = true) {
                BiliDownloadService.resume(context, task.taskId, task.bvid, task.aid, task.title, task.qn, task.episodeId, task.videoCodec, task.audioCodec, task.pageIndex, task.cover, task.author)
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
        if (task.status == "completed" || task.status == "failed" || task.status == "cancelled") {
            action("移除记录") {
                AlertDialog.Builder(context).setMessage("只移除这条下载记录，不会删除已经保存的视频文件。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch { dao.delete(task.taskId) }
                    }.show()
            }
        }
        card.addView(actions)
        b.downloadTaskControls.addView(card)
    }


    /** 将已完成任务复制到用户通过系统文件选择器授权的目录，不移动或删除原文件。 */
    private fun exportCompletedFiles(treeUri: Uri) {
        val appContext = requireContext().applicationContext
        val targets = currentTasks.filter { it.status == "completed" && it.outputPath.isNotBlank() }
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "没有可导出的已完成文件", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val context = appContext
                val resolver = context.contentResolver
                val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri)
                )
                var exported = 0
                var failed = 0
                targets.forEachIndexed { index, task ->
                    val baseName = task.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .trim().take(70).ifBlank { "B站视频" }
                    val name = if (index == 0) "$baseName.mp4" else "$baseName-$index.mp4"
                    var destination: Uri? = null
                    try {
                        destination = DocumentsContract.createDocument(
                            resolver, rootDocument, "video/mp4", name
                        ) ?: error("无法在目标目录创建文件")
                        val source = Uri.parse(task.outputPath)
                        val input = if (source.scheme == "file") {
                            java.io.FileInputStream(java.io.File(source.path.orEmpty()))
                        } else resolver.openInputStream(source) ?: error("无法读取源视频")
                        input.use { from ->
                            resolver.openOutputStream(destination, "w")?.use { to ->
                                from.copyTo(to)
                                to.flush()
                            } ?: error("无法写入目标文件")
                        }
                        exported++
                    } catch (_: Exception) {
                        failed++
                        destination?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                    }
                }
                exported to failed
            }
            if (_binding != null && isAdded) {
                val (exported, failed) = result
                Toast.makeText(
                    appContext,
                    "导出完成：成功 $exported 个，失败 $failed 个",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
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

    /**
     * BiliDownload 批量重试语义：使用原任务 ID 和原下载规格，不创建重复记录；
     * 同时恢复下载失败与用户取消的任务，并保留封面、UP 主等展示元数据。
     */
    private fun retryFailedTasks() {
        viewLifecycleOwner.lifecycleScope.launch {
            val failed = dao.tasksByStatus("failed")
            val cancelled = dao.tasksByStatus("cancelled")
            val targets = (failed + cancelled).distinctBy { it.taskId }
            val activeIds = currentTasks
                .filter { it.status == "queued" || it.status == "downloading" }
                .map { it.taskId }
                .toSet()
            var submitted = 0
            var skipped = 0
            targets.forEach { task ->
                val hasIdentity = task.bvid.isNotBlank() || task.aid > 0L || task.episodeId > 0L
                if (!hasIdentity || task.taskId in activeIds) {
                    skipped++
                } else {
                    runCatching {
                        BiliDownloadService.enqueue(
                            requireContext(), task.taskId, task.bvid, task.aid,
                            task.title, task.qn, task.episodeId, task.videoCodec,
                            task.audioCodec, task.pageIndex, task.cover, task.author
                        )
                    }.onSuccess { submitted++ }.onFailure { skipped++ }
                }
            }
            b.downloadSummary.text = when {
                targets.isEmpty() -> "没有可重试的失败或已取消任务"
                else -> "已重新提交 $submitted 个任务" +
                    if (skipped > 0) " · 跳过 $skipped 个" else ""
            }
        }
    }

    private fun updateSpeedSamples(tasks: List<DownloadTaskEntity>) {
        val now = android.os.SystemClock.elapsedRealtime()
        val activeIds = tasks.filter { it.status == "downloading" }.map { it.taskId }.toSet()
        speedSamples.keys.retainAll(activeIds)
        currentSpeeds.keys.retainAll(activeIds)
        tasks.filter { it.status == "downloading" }.forEach { task ->
            val previous = speedSamples[task.taskId]
            if (previous != null && now > previous.atMs && task.completedBytes >= previous.bytes) {
                val elapsed = now - previous.atMs
                if (elapsed >= 250L) currentSpeeds[task.taskId] =
                    ((task.completedBytes - previous.bytes) * 1000L / elapsed).coerceAtLeast(0L)
            }
            speedSamples[task.taskId] = SpeedSample(task.completedBytes, now)
        }
    }

    private fun formatBytesPerSecond(value: Long): String {
        if (value <= 0L) return "计算中…"
        val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
        var amount = value.toDouble()
        var unit = 0
        while (amount >= 1024.0 && unit < units.lastIndex) {
            amount /= 1024.0
            unit++
        }
        return String.format(java.util.Locale.getDefault(), if (unit == 0) "%.0f %s" else "%.1f %s", amount, units[unit])
    }

    private fun formatEtaSeconds(value: Long): String {
        if (value <= 0L) return "即将完成"
        val hours = value / 3600L
        val minutes = value / 60L % 60L
        val seconds = value % 60L
        return when {
            hours > 0L -> hours.toString() + "小时" + minutes + "分"
            minutes > 0L -> minutes.toString() + "分" + seconds + "秒"
            else -> seconds.toString() + "秒"
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

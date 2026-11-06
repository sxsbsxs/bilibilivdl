package com.sxsbsxs.bilivdl.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.sxsbsxs.bilivdl.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class LocalDownloadsActivity : AppCompatActivity() {
    private data class LocalVideo(val uri: Uri, val name: String, val size: Long, val date: Long, val duration: Long)
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var search: EditText
    private lateinit var selectionBar: LinearLayout
    private var allFiles: List<LocalVideo> = emptyList()
    private val selected = linkedSetOf<String>()
    private var sortMode = 0
    private var selectionMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(getColor(R.color.background_dark))
        }
        root.addView(TextView(this).apply {
            text = "‹  已下载文件"; textSize = 21f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(12)); setOnClickListener { finish() }
        })
        search = EditText(this).apply {
            hint = "搜索已下载视频"; textSize = 14f; setSingleLine(true)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            setPadding(dp(12), 0, dp(12), 0)
            background = getDrawable(R.drawable.bg_empty_card)
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(44)))
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        summary = TextView(this).apply {
            textSize = 12f; setTextColor(getColor(R.color.text_secondary))
        }
        toolbar.addView(summary, LinearLayout.LayoutParams(0, dp(36), 1f))
        toolbar.addView(toolButton("最新") { sortMode = 0; renderFiles() })
        toolbar.addView(toolButton("最大") { sortMode = 1; renderFiles() })
        toolbar.addView(toolButton("名称") { sortMode = 2; renderFiles() })
        root.addView(toolbar)
        selectionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE
        }
        selectionBar.addView(TextView(this).apply {
            text = "选择项目"; setTextColor(getColor(R.color.text_primary)); textSize = 13f
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        selectionBar.addView(toolButton("全选") { toggleSelectAll() })
        selectionBar.addView(toolButton("取消") { selected.clear(); selectionMode = false; renderFiles() })
        selectionBar.addView(toolButton("删除") { confirmDeleteSelected() })
        root.addView(selectionBar)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderFiles() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        loadFiles()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) loadFiles() }

    private fun loadFiles() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { queryFiles() }
            allFiles = files
            selected.retainAll(files.map { it.uri.toString() }.toSet())
            renderFiles()
        }
    }

    private fun queryFiles(): List<LocalVideo> {
        val result = mutableListOf<LocalVideo>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = if (Build.VERSION.SDK_INT >= 29) arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION, MediaStore.MediaColumns.RELATIVE_PATH
        ) else arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA)
        val selection = if (Build.VERSION.SDK_INT >= 29)
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        else "${MediaStore.Video.Media.DATA} LIKE ? OR ${MediaStore.Video.Media.DATA} LIKE ?"
        val args = arrayOf("%BiliVdl%", "%BiliDownload%")
        contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                result += LocalVideo(
                    ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    c.getString(nameCol) ?: "视频", c.getLong(sizeCol), c.getLong(dateCol), c.getLong(durationCol)
                )
            }
        }
        return result
    }

    private fun renderFiles() {
        if (!::list.isInitialized) return
        val query = search.text?.toString()?.trim().orEmpty()
        val filtered = allFiles.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
            .let { rows -> when (sortMode) {
                1 -> rows.sortedByDescending { it.size }
                2 -> rows.sortedBy { it.name.lowercase() }
                else -> rows.sortedByDescending { it.date }
            } }
        summary.text = "共 ${filtered.size} 个视频" + if (selected.isNotEmpty()) " · 已选 ${selected.size}" else ""
        selectionBar.visibility = if (selectionMode) android.view.View.VISIBLE else android.view.View.GONE
        list.removeAllViews()
        if (filtered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (query.isBlank()) "还没有找到下载视频\n\n下载完成后，文件会出现在这里。" else "没有匹配的视频"
                textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(20), dp(56), dp(20), dp(56))
                setTextColor(getColor(R.color.text_secondary))
            })
        } else filtered.forEach(::addFileCard)
    }

    private fun addFileCard(file: LocalVideo) {
        val key = file.uri.toString()
        val isSelected = key in selected
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = getDrawable(R.drawable.bg_surface_card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
            isClickable = true; isFocusable = true
        }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "视频封面"
        }
        card.addView(thumb, LinearLayout.LayoutParams(dp(108), dp(70)))
        Glide.with(this).load(file.uri).centerCrop().placeholder(R.drawable.bg_empty_card).into(thumb)
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        text.addView(TextView(this).apply {
            this.text = file.name; maxLines = 2; textSize = 13f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        text.addView(TextView(this).apply {
            val size = android.text.format.Formatter.formatFileSize(this@LocalDownloadsActivity, file.size)
            val mins = file.duration / 60000L
            val secs = file.duration / 1000L % 60L
            this.text = "$size  ·  ${mins}:${secs.toString().padStart(2, '0')}\n${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.date * 1000L))}"
            textSize = 11f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(4), 0, 0)
        })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (selectionMode) card.addView(TextView(this).apply {
            this.text = if (isSelected) "✓" else "○"; textSize = 20f
            setTextColor(getColor(R.color.brand)); setPadding(dp(5), dp(4), dp(5), dp(4))
        })
        card.setOnClickListener {
            if (selectionMode) {
                if (isSelected) selected.remove(key) else selected.add(key)
                if (selected.isEmpty()) selectionMode = false
                renderFiles()
            } else openFile(file)
        }
        card.setOnLongClickListener {
            selectionMode = true
            if (isSelected) selected.remove(key) else selected.add(key)
            renderFiles()
            true
        }
        list.addView(card)
    }

    private fun openFile(file: LocalVideo) {
        startActivity(Intent(this, LocalMediaPlayerActivity::class.java)
            .putExtra(LocalMediaPlayerActivity.EXTRA_URI, file.uri.toString())
            .putExtra(LocalMediaPlayerActivity.EXTRA_TITLE, file.name))
    }

    private fun toggleSelectAll() {
        val query = search.text?.toString()?.trim().orEmpty()
        val visible = allFiles.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        if (visible.all { it.uri.toString() in selected }) selected.clear()
        else selected.addAll(visible.map { it.uri.toString() })
        selectionMode = selected.isNotEmpty()
        renderFiles()
    }

    private fun confirmDeleteSelected() {
        val targets = allFiles.filter { it.uri.toString() in selected }
        if (targets.isEmpty()) return
        AlertDialog.Builder(this).setTitle("删除已选文件")
            .setMessage("确定删除 ${targets.size} 个本地视频？此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { targets.forEach { runCatching { contentResolver.delete(it.uri, null, null) } } }
                    selected.clear(); selectionMode = false; loadFiles()
                }
            }.show()
    }

    private fun toolButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER
        setTextColor(getColor(R.color.brand_dark))
        setPadding(dp(10), 0, dp(10), 0); minHeight = dp(34)
        background = getDrawable(R.drawable.bg_pill_soft)
        foreground = getDrawable(android.R.drawable.list_selector_background)
        setOnClickListener { action() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

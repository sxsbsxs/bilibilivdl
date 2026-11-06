package com.sxsbsxs.bilivdl.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
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

class LocalDownloadsActivity : AppCompatActivity() {
    private data class LocalVideo(val uri: Uri, val name: String, val size: Long, val date: Long, val duration: Long)
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(16)); setBackgroundColor(getColor(R.color.background_dark)) }
        root.addView(TextView(this).apply { text = "‹  已下载文件"; textSize = 22f; setTextColor(getColor(R.color.text_primary)); setPadding(0, 0, 0, dp(12)); setOnClickListener { finish() } })
        summary = TextView(this).apply { text = "正在扫描本地视频…"; textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, 0, 0, dp(12)) }
        root.addView(summary)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadFiles()
    }

    override fun onResume() { super.onResume(); if (::list.isInitialized) loadFiles() }

    private fun loadFiles() {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) { queryFiles() }
            list.removeAllViews()
            summary.text = "共找到 ${files.size} 个本地视频 · 清理下载记录不会删除这些文件"
            if (files.isEmpty()) {
                list.addView(TextView(this@LocalDownloadsActivity).apply {
                    text = "还没有找到下载视频\n\n下载完成后，文件会出现在这里。"
                    textSize = 14f; gravity = Gravity.CENTER; setPadding(dp(20), dp(56), dp(20), dp(56))
                    setTextColor(getColor(R.color.text_secondary))
                })
            } else files.forEach(::addFileCard)
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
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            args = arrayOf("%BiliVdl%", "%BiliDownload%")
        } else {
            selection = "${MediaStore.Video.Media.DATA} LIKE ? OR ${MediaStore.Video.Media.DATA} LIKE ?"
            args = arrayOf("%BiliVdl%", "%BiliDownload%")
        }
        contentResolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += LocalVideo(ContentUris.withAppendedId(collection, id), c.getString(nameCol) ?: "视频",
                    c.getLong(sizeCol), c.getLong(dateCol), c.getLong(durationCol))
            }
        }
        return result
    }

    private fun addFileCard(file: LocalVideo) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = getDrawable(R.drawable.bg_empty_card)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
            isClickable = true; isFocusable = true
        }
        val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "视频封面" }
        card.addView(thumb, LinearLayout.LayoutParams(dp(112), dp(72)))
        Glide.with(this).load(file.uri).centerCrop().placeholder(R.drawable.bg_empty_card).into(thumb)
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        text.addView(TextView(this).apply { this.text = file.name; maxLines = 2; textSize = 14f; setTextColor(getColor(R.color.text_primary)); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        text.addView(TextView(this).apply {
            val size = android.text.format.Formatter.formatFileSize(this@LocalDownloadsActivity, file.size)
            val mins = file.duration / 60000L
            val secs = file.duration / 1000L % 60L
            this.text = "$size  ·  ${mins}:${secs.toString().padStart(2, '0')}"
            textSize = 12f; setTextColor(getColor(R.color.text_secondary)); setPadding(0, dp(5), 0, 0)
        })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        card.setOnClickListener {
            startActivity(Intent(this, LocalMediaPlayerActivity::class.java).putExtra(LocalMediaPlayerActivity.EXTRA_URI, file.uri.toString()).putExtra(LocalMediaPlayerActivity.EXTRA_TITLE, file.name))
        }
        card.setOnLongClickListener {
            AlertDialog.Builder(this).setItems(arrayOf("播放", "分享", "删除")) { _, which ->
                when (which) {
                    0 -> card.performClick()
                    1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, file.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "分享视频"))
                    2 -> AlertDialog.Builder(this).setMessage("删除本地视频文件？此操作不可撤销。").setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                        contentResolver.delete(file.uri, null, null); loadFiles()
                    }.show()
                }
            }.show()
            true
        }
        list.addView(card)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

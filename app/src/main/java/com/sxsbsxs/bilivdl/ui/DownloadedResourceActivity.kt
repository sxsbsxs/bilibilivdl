package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.R

/** BVD 风格的本地资源详情：文件信息、播放、分享和删除。 */
class DownloadedResourceActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URI = "resource_uri"
        const val EXTRA_TITLE = "resource_title"
        const val EXTRA_QUALITY = "resource_quality"
    }

    private lateinit var resourceUri: Uri
    private lateinit var titleText: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_URI).orEmpty()
        val parsed = runCatching { Uri.parse(raw) }.getOrNull()
        if (parsed == null) { finish(); return }
        resourceUri = parsed
        titleText = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "已下载视频" }
        val quality = intent.getStringExtra(EXTRA_QUALITY).orEmpty()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(getColor(R.color.background_dark))
        }
        root.addView(TextView(this).apply {
            text = "‹    文件详情"
            textSize = 22f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, 0, 0, dp(16))
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = titleText
            textSize = 19f
            maxLines = 3
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = buildInfo(quality)
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(16), 0, dp(22))
        })
        addAction(root, "播放视频") { playResource() }
        addAction(root, "分享文件") { shareResource() }
        addAction(root, "删除文件") { confirmDelete() }
        setContentView(root)
    }

    private fun buildInfo(quality: String): String {
        var name = titleText
        var size = -1L
        var date = ""
        var mime = "video/*"
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.MIME_TYPE)
        runCatching {
            contentResolver.query(resourceUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.string(MediaStore.MediaColumns.DISPLAY_NAME) ?: name
                    size = cursor.long(MediaStore.MediaColumns.SIZE) ?: size
                    date = cursor.long(MediaStore.MediaColumns.DATE_ADDED)?.let {
                        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(it * 1000))
                    }.orEmpty()
                    mime = cursor.string(MediaStore.MediaColumns.MIME_TYPE) ?: mime
                }
            }
        }
        val sizeText = if (size >= 0) android.text.format.Formatter.formatFileSize(this, size) else "未知"
        return "文件名：" + name + "\n清晰度：" + quality.ifBlank { "未知" } +
            "\n文件大小：" + sizeText + "\n保存时间：" + date.ifBlank { "未知" } +
            "\n文件类型：" + mime + "\n保存位置：系统视频库 · Movies/BiliVdl"
    }

    private fun playResource() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(resourceUri, contentResolver.getType(resourceUri) ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "没有找到可播放此视频的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareResource() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(resourceUri) ?: "video/*"
            putExtra(Intent.EXTRA_STREAM, resourceUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "分享视频"))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this).setTitle("删除下载文件")
            .setMessage("将从系统视频库中永久删除这个文件，是否继续？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val deleted = runCatching { contentResolver.delete(resourceUri, null, null) }.getOrDefault(0)
                if (deleted > 0) {
                    Toast.makeText(this, "文件已删除", Toast.LENGTH_SHORT).show()
                    finish()
                } else Toast.makeText(this, "删除失败，可能已被移动或没有权限", Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun addAction(root: LinearLayout, label: String, action: () -> Unit) {
        root.addView(MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { bottomMargin = dp(9) }
        })
    }

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

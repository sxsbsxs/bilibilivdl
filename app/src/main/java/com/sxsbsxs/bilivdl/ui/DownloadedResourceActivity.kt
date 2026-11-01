package com.sxsbsxs.bilivdl.ui

import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.sxsbsxs.bilivdl.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** 本地资源详情：信息、播放、音频提取、分享和删除。 */
class DownloadedResourceActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URI = "resource_uri"
        const val EXTRA_TITLE = "resource_title"
        const val EXTRA_QUALITY = "resource_quality"
    }

    private lateinit var resourceUri: Uri
    private lateinit var titleText: String
    private var extractedAudioUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_URI).orEmpty()
        if (raw.isBlank()) { finish(); return }
        resourceUri = Uri.parse(raw)
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
        addAction(root, "提取音频（保存为 M4A）") { extractAudio() }
        addAction(root, "分享视频") { shareUri(resourceUri, "video/*", "分享视频") }
        addAction(root, "分享提取的音频") {
            extractedAudioUri?.let { shareUri(it, "audio/mp4", "分享音频") }
                ?: Toast.makeText(this, "请先提取音频", Toast.LENGTH_SHORT).show()
        }
        addAction(root, "删除视频文件") { confirmDelete() }
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
        return "文件名：$name\n清晰度：${quality.ifBlank { "未知" }}\n文件大小：$sizeText" +
            "\n保存时间：${date.ifBlank { "未知" }}\n文件类型：$mime\n保存位置：系统视频库 · Movies/BiliVdl"
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

    private fun shareUri(uri: Uri, mime: String, chooser: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, chooser))
    }

    /**
     * Remuxes the existing AAC audio track without re-encoding. No network request,
     * external encoder, or quality loss is involved. Android 10+ MediaStore owns output.
     */
    private fun extractAudio() {
        if (Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "音频提取目前需要 Android 10 或更高版本", Toast.LENGTH_LONG).show()
            return
        }
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        val dialog = AlertDialog.Builder(this).setTitle("正在提取音频")
            .setMessage("正在读取视频音轨并封装为 M4A，请稍候。")
            .setView(progress).create()
        dialog.show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { remuxAudioTrack() } }
            dialog.dismiss()
            result.onSuccess { uri ->
                extractedAudioUri = uri
                Toast.makeText(this@DownloadedResourceActivity, "音频已保存到音乐库", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(this@DownloadedResourceActivity,
                    "提取失败：${error.message ?: "不支持的音频格式"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun remuxAudioTrack(): Uri {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var outputUri: Uri? = null
        var outputFd: ParcelFileDescriptor? = null
        var inputFd: ParcelFileDescriptor? = null
        var muxerStarted = false
        try {
            inputFd = contentResolver.openFileDescriptor(resourceUri, "r") ?: error("无法读取视频文件")
            extractor.setDataSource(inputFd!!.fileDescriptor)
            var sourceTrack = -1
            var sourceFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    if (mime != "audio/mp4a-latm") error("当前音轨不是 AAC，暂不支持无损封装为 M4A（格式：$mime）")
                    sourceTrack = i
                    sourceFormat = format
                    break
                }
            }
            if (sourceTrack < 0 || sourceFormat == null) error("视频中没有可提取的音轨")
            val safeName = titleText.replace(Regex("""[\\/:*?"<>|]"""), "_").take(70).ifBlank { "bilibili_audio" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$safeName.m4a")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/BiliVdl")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法在音乐库创建输出文件")
            outputFd = contentResolver.openFileDescriptor(outputUri, "w") ?: error("无法写入音频文件")
            muxer = MediaMuxer(outputFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            extractor.selectTrack(sourceTrack)
            val outputTrack = muxer.addTrack(sourceFormat)
            muxer.start()
            muxerStarted = true
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null
            outputFd.close()
            outputFd = null
            contentResolver.update(outputUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            return outputUri
        } catch (error: Exception) {
            if (outputUri != null) runCatching { contentResolver.delete(outputUri, null, null) }
            throw error
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { outputFd?.close() }
            runCatching { inputFd?.close() }
            extractor.release()
        }
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

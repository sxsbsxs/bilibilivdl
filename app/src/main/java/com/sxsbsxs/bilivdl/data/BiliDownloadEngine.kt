package com.sxsbsxs.bilivdl.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** BVD-inspired resumable DASH transfer and FFmpeg stream-copy muxing. GPL-3.0. */
object BiliDownloadEngine {
    private const val BUFFER_SIZE = 262144
    private const val MAX_RETRIES = 4

    suspend fun download(context: Context, playback: PlaybackInfo, title: String, cookie: String,
        onProgress: (Long, Long, String) -> Unit): String = withContext(Dispatchers.IO) {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "bilibili_video" }
        val dir = File(context.cacheDir, "bili-download").apply { mkdirs() }
        val headers = mutableMapOf("User-Agent" to BiliHeaders.UA, "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com", "Accept" to "*/*")
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        val merged = File(dir, "$safe.final.mp4")
        try {
            if (playback.isDash) {
                val video = playback.videoUrl ?: error("DASH 视频轨道缺失")
                val audio = playback.audioUrl ?: error("DASH 音频轨道缺失")
                val vf = File(dir, "$safe.video.m4s")
                val af = File(dir, "$safe.audio.m4s")
                coroutineScope {
                    val v = async { transfer(video, vf, headers) { n, _ -> onProgress(n + af.length(), -1, "下载视频轨道") } }
                    val a = async { transfer(audio, af, headers) { n, _ -> onProgress(n + vf.length(), -1, "下载音频轨道") } }
                    v.await(); a.await()
                }
                onProgress(0, 0, "正在合并音视频")
                val session = FFmpegKit.executeWithArguments(arrayOf("-y", "-i", vf.absolutePath, "-i", af.absolutePath,
                    "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", merged.absolutePath))
                if (!ReturnCode.isSuccess(session.returnCode) || !merged.isFile || merged.length() == 0L)
                    error("音视频合并失败（FFmpeg \${session.returnCode}）")
                vf.delete(); af.delete()
            } else {
                transfer(playback.url, merged, headers) { n, total -> onProgress(n, total, "下载视频") }
            }
            publish(context, merged, safe)
        } catch (error: Exception) {
            merged.delete()
            throw error
        }
    }

    private suspend fun transfer(url: String, file: File, headers: Map<String, String>,
        progress: (Long, Long) -> Unit) {
        var last: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try { transferOnce(url, file, headers, progress); return }
            catch (e: Exception) { last = e; if (attempt == MAX_RETRIES) break; delay((1000L shl attempt).coerceAtMost(8000L)) }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    private suspend fun transferOnce(url: String, file: File, headers: Map<String, String>,
        progress: (Long, Long) -> Unit) {
        val offset = if (file.exists()) file.length() else 0L
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
            headers.forEach { (k,v) -> setRequestProperty(k,v) }
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = c.responseCode
            if (code != 200 && code != 206) error("CDN 返回 HTTP $code")
            val append = offset > 0 && code == 206
            if (append) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(c.getHeaderField("Content-Range").orEmpty())
                if (range?.groupValues?.get(1)?.toLongOrNull() != offset) {
                    RandomAccessFile(file,"rw").use { it.setLength(0) }
                    error("续传范围不匹配")
                }
            }
            val start = if (append) offset else 0L
            val len = c.getHeaderFieldLong("Content-Length", -1L)
            val total = if (len >= 0) start + len else -1L
            RandomAccessFile(file,"rw").use { out ->
                if (!append) out.setLength(0)
                out.seek(start)
                var done = start
                val input = c.inputStream
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer,0,n); done += n; progress(done,total)
                }
                if (done == 0L || (total >= 0 && done != total)) error("资源长度校验失败：$done/$total")
            }
        } finally { c.disconnect() }
    }


    /** 使用 Android MediaExtractor/MediaMuxer 直接复制 DASH 轨道，不重新编码。 */
    private fun muxDashTracks(video: File, audio: File, output: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = (0 until videoExtractor.trackCount).firstOrNull {
                videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("下载的视频资源中没有视频轨道")
            val audioTrack = (0 until audioExtractor.trackCount).firstOrNull {
                audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("下载的音频资源中没有音频轨道")
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoBuffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val audioBuffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoTime = videoExtractor.sampleTime
            var audioTime = audioExtractor.sampleTime
            while (videoTime >= 0 || audioTime >= 0) {
                val takeVideo = audioTime < 0 || (videoTime >= 0 && videoTime <= audioTime)
                val extractor = if (takeVideo) videoExtractor else audioExtractor
                val buffer = if (takeVideo) videoBuffer else audioBuffer
                val targetTrack = if (takeVideo) outVideo else outAudio
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (takeVideo) videoTime = -1 else audioTime = -1
                    continue
                }
                info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(targetTrack, buffer, info)
                extractor.advance()
                if (takeVideo) videoTime = extractor.sampleTime else audioTime = extractor.sampleTime
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun publish(context: Context, file: File, title: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "\${Environment.DIRECTORY_MOVIES}/BiliVdl")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建系统媒体库文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output -> file.inputStream().use { it.copyTo(output, BUFFER_SIZE) } }
                ?: error("无法写入系统媒体库")
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            file.delete()
            return uri.toString()
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}

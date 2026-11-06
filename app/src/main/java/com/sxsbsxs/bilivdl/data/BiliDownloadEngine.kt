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
import kotlinx.coroutines.CancellationException
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

/** BVD-inspired resumable DASH transfer and Android native stream muxing. GPL-3.0. */
object BiliDownloadEngine {
    private const val BUFFER_SIZE = 262144
    private const val MAX_RETRIES = 4

    suspend fun download(context: Context, playback: PlaybackInfo, title: String, cookie: String,
        taskId: String = title.hashCode().toString(),
        onProgress: (Long, Long, String) -> Unit): String = withContext(Dispatchers.IO) {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "bilibili_video" }
        val taskKey = taskId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        val fileStem = "$safe.$taskKey"
        val dir = File(context.cacheDir, "bili-download").apply { mkdirs() }
        // CDN media requests use BiliDroid's UA. Never forward the Bilibili account Cookie to CDN hosts.
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 BiliDroid/1.41.0 (bbcallen@gmail.com)",
            "Referer" to "https://www.bilibili.com/",
            "Accept" to "*/*"
        )
        val merged = File(dir, "$fileStem.final.mp4")
        try {
            if (playback.isDash) {
                val video = playback.videoUrl ?: error("DASH 视频轨道缺失")
                val audio = playback.audioUrl ?: error("DASH 音频轨道缺失")
                val vf = File(dir, "$fileStem.video.m4s")
                val af = File(dir, "$fileStem.audio.m4s")
                coroutineScope {
                    val v = async { transferCandidates(listOf(video) + playback.videoBackupUrls, vf, headers) { n, _ -> onProgress(n + af.length(), -1, "下载视频轨道") } }
                    val a = async { transferCandidates(listOf(audio) + playback.audioBackupUrls, af, headers) { n, _ -> onProgress(n + vf.length(), -1, "下载音频轨道") } }
                    v.await(); a.await()
                }
                onProgress(0, 0, "正在合并音视频")
                muxDashTracks(vf, af, merged)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!merged.isFile || merged.length() == 0L) error("音视频合并失败：输出文件为空")
                vf.delete(); af.delete()
            } else if (playback.segments.size > 1) {
                // DURL 多段视频需要按接口返回顺序全部下载并顺序拼接。
                val segmentFiles = playback.segments.mapIndexed { index, _ ->
                    File(dir, "$fileStem.segment$index.tmp")
                }
                segmentFiles.forEachIndexed { index, segmentFile ->
                    transfer(playback.segments[index], segmentFile, headers) { n, _ ->
                        val completed = segmentFiles.take(index).sumOf { it.length() } + n
                        onProgress(completed, -1, "下载视频分段 ${index + 1}/${segmentFiles.size}")
                    }
                }
                muxDurlSegments(segmentFiles, merged)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // Keep partial segments when cancelled; remove only after a successful mux.
                segmentFiles.forEach { it.delete() }
            } else {
                transferCandidates(listOf(playback.url), merged, headers) { n, total -> onProgress(n, total, "下载视频") }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            publish(context, merged, safe)
        } catch (cancelled: CancellationException) {
            // Preserve partial transfers so pause/restart can continue from the last byte.
            throw cancelled
        } catch (error: Exception) {
            // Keep network partials; the next retry validates HTTP Range before appending.
            throw error
        }
    }

    fun cleanupTask(context: Context, taskId: String, title: String) {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(70).ifBlank { "bilibili_video" }
        val taskKey = taskId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48)
        File(context.cacheDir, "bili-download").listFiles()
            ?.filter { it.name.contains(".$taskKey.") }
            ?.forEach { it.delete() }
    }

    /** 按 BVD 的候选源轮换策略处理 CDN 403；切换源时丢弃不可信的跨源临时前缀。 */
    private suspend fun transferCandidates(urls: List<String>, file: File, headers: Map<String, String>,
        progress: (Long, Long) -> Unit) {
        val candidates = urls.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
        require(candidates.isNotEmpty()) { "B站没有返回可用的 CDN 地址" }
        var last: Exception? = null
        for ((index, url) in candidates.withIndex()) {
            try {
                transfer(url, file, headers, progress)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                last = error
                if (index < candidates.lastIndex) {
                    file.delete()
                    progress(0L, -1L)
                }
            }
        }
        throw last ?: IllegalStateException("所有 CDN 地址均不可用")
    }

    private class CdnHttpException(val status: Int) : java.io.IOException("CDN 返回 HTTP " + status)

    private suspend fun transfer(url: String, file: File, headers: Map<String, String>,
        progress: (Long, Long) -> Unit) {
        var last: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            try { transferOnce(url, file, headers, progress); return }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { last = e; if (e is CdnHttpException && (e.status == 403 || e.status == 412)) break; if (attempt == MAX_RETRIES) break; delay((1000L shl attempt).coerceAtMost(8000L)) }
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
        // Disconnect the blocking socket immediately when the owning coroutine is cancelled.
        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause ->
                if (cause is CancellationException) c.disconnect()
            }
        try {
            val code = c.responseCode
            if (code == 416 && offset > 0L) {
                val total = Regex("bytes \\*/(\\d+)").matchEntire(c.getHeaderField("Content-Range").orEmpty())
                    ?.groupValues?.get(1)?.toLongOrNull()
                if (total != null && total == offset) {
                    progress(offset, total)
                    return
                }
            }
            if (code != 200 && code != 206) throw CdnHttpException(code)
            val append = offset > 0 && code == 206
            var rangeTotal = -1L
            if (append) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(c.getHeaderField("Content-Range").orEmpty())
                val rangeStart = range?.groupValues?.get(1)?.toLongOrNull()
                val rangeEnd = range?.groupValues?.get(2)?.toLongOrNull()
                rangeTotal = range?.groupValues?.get(3)?.takeIf { it != "*" }?.toLongOrNull() ?: -1L
                val length = c.getHeaderFieldLong("Content-Length", -1L)
                val rangeLength = if (rangeStart != null && rangeEnd != null) rangeEnd - rangeStart + 1L else -1L
                if (rangeStart != offset || rangeEnd == null || rangeEnd < offset ||
                    (rangeTotal >= 0L && rangeEnd >= rangeTotal) ||
                    (length >= 0L && rangeLength != length)) {
                    RandomAccessFile(file,"rw").use { it.setLength(0) }
                    error("服务器返回的续传范围与本地文件不匹配，已清除不可信临时片段")
                }
            }
            val start = if (append) offset else 0L
            val len = c.getHeaderFieldLong("Content-Length", -1L)
            val total = if (rangeTotal > 0L) rangeTotal else if (len >= 0) start + len else -1L
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



    /**
     * 合并 B站 DURL 的多个 MP4 分段。不能直接拼接 MP4 文件字节；
     * 这里按媒体轨道读取样本，并为每段增加前一段的时间偏移。
     */
    private suspend fun muxDurlSegments(segments: List<File>, output: File) {
        output.delete()
        require(segments.isNotEmpty()) { "没有可合并的视频分段" }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTracks = linkedMapOf<String, Int>()
        val buffer = ByteBuffer.allocate(16 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var started = false
        var timeOffsetUs = 0L
        try {
            for ((segmentIndex, file) in segments.withIndex()) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val trackMap = linkedMapOf<Int, Int>()
                    var segmentDurationUs = 0L
                    for (track in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(track)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        val outTrack = outputTracks[mime] ?: run {
                            if (started) error("DURL 分段媒体轨道发生变化：" + mime)
                            muxer.addTrack(format).also { outputTracks[mime] = it }
                        }
                        trackMap[track] = outTrack
                        if (format.containsKey(MediaFormat.KEY_DURATION)) {
                            segmentDurationUs = maxOf(segmentDurationUs, format.getLong(MediaFormat.KEY_DURATION))
                        }
                        extractor.selectTrack(track)
                    }
                    if (trackMap.isEmpty()) error("第 " + (segmentIndex + 1) + " 段没有可识别的媒体轨道")
                    if (!started) {
                        muxer.start()
                        started = true
                    }
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val track = extractor.sampleTrackIndex
                        if (track < 0) break
                        val outTrack = trackMap[track]
                        if (outTrack == null) {
                            extractor.advance()
                            continue
                        }
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val pts = extractor.sampleTime.coerceAtLeast(0L) + timeOffsetUs
                        info.set(0, size, pts, extractor.sampleFlags)
                        muxer.writeSampleData(outTrack, buffer, info)
                        extractor.advance()
                    }
                    timeOffsetUs += segmentDurationUs
                } finally {
                    extractor.release()
                }
            }
            if (!started) error("所有 DURL 分段均无法解析")
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    /** 使用 Android MediaExtractor/MediaMuxer 直接复制 DASH 轨道，不重新编码。 */
    private suspend fun muxDashTracks(video: File, audio: File, output: File) {
        output.delete()
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
            val activeMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val outVideo = activeMuxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = activeMuxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            activeMuxer.start()
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoBuffer = ByteBuffer.allocate(16 * 1024 * 1024)
            val audioBuffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoTime = videoExtractor.sampleTime
            var audioTime = audioExtractor.sampleTime
            while (videoTime >= 0 || audioTime >= 0) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
                activeMuxer.writeSampleData(targetTrack, buffer, info)
                extractor.advance()
                if (takeVideo) videoTime = extractor.sampleTime else audioTime = extractor.sampleTime
            }
            activeMuxer.stop()
        } finally {
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private suspend fun publish(context: Context, file: File, title: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BiliVdl")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建系统媒体库文件")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            } ?: error("无法写入系统媒体库")
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            file.delete()
            return uri.toString()
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }
}

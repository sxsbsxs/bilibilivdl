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
import org.json.JSONObject

/** BVD-inspired resumable DASH transfer and Android native stream muxing. GPL-3.0. */
object BiliDownloadEngine {
    private const val BUFFER_SIZE = 262144
    private const val MAX_RETRIES = 4

    suspend fun download(context: Context, playback: PlaybackInfo, title: String, cookie: String,
        taskId: String = title.hashCode().toString(),
        refreshPlayback: suspend () -> PlaybackInfo = { playback },
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
                    val v = async { transferCandidates(listOf(video) + playback.videoBackupUrls, vf, headers,
                        refreshCandidates = { val fresh = refreshPlayback(); listOfNotNull(fresh.videoUrl ?: fresh.url) + fresh.videoBackupUrls }) { n, _ -> onProgress(n + af.length(), -1, "下载视频轨道") } }
                    val a = async { transferCandidates(listOf(audio) + playback.audioBackupUrls, af, headers,
                        refreshCandidates = { val fresh = refreshPlayback(); listOfNotNull(fresh.audioUrl) + fresh.audioBackupUrls }) { n, _ -> onProgress(n + vf.length(), -1, "下载音频轨道") } }
                    v.await(); a.await()
                }
                onProgress(0, 0, "正在合并音视频")
                muxDashTracks(vf, af, merged)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!merged.isFile || merged.length() == 0L) error("音视频合并失败：输出文件为空")
                validateMuxedOutput(merged, requireAudio = true)
                vf.delete(); af.delete()
            } else if (playback.segments.size > 1) {
                // DURL 多段视频需要按接口返回顺序全部下载并顺序拼接。
                val segmentFiles = playback.segments.mapIndexed { index, _ ->
                    File(dir, "$fileStem.segment$index.tmp")
                }
                segmentFiles.forEachIndexed { index, segmentFile ->
                    transferCandidates(listOf(playback.segments[index]), segmentFile, headers,
                        refreshCandidates = {
                            val fresh = refreshPlayback()
                            listOfNotNull(fresh.segments.getOrNull(index), fresh.url)
                        }) { n, _ ->
                        val completed = segmentFiles.take(index).sumOf { it.length() } + n
                        onProgress(completed, -1, "下载视频分段 ${index + 1}/${segmentFiles.size}")
                    }
                }
                muxDurlSegments(segmentFiles, merged)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // Keep partial segments when cancelled; remove only after a successful mux.
                validateMuxedOutput(merged, requireAudio = false)
                segmentFiles.forEach { it.delete() }
            } else {
                transferCandidates(listOf(playback.url), merged, headers,
                    refreshCandidates = {
                        val fresh = refreshPlayback()
                        listOfNotNull(fresh.videoUrl ?: fresh.url) + fresh.videoBackupUrls + fresh.segments
                    }) { n, total -> onProgress(n, total, "下载视频") }
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
        refreshCandidates: (suspend () -> List<String>)? = null,
        progress: (Long, Long) -> Unit) {
        val candidates = urls.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct().toMutableList()
        require(candidates.isNotEmpty()) { "B站没有返回可用的 CDN 地址" }
        var last: Exception? = null
        var index = 0
        var refreshed = false
        while (index < candidates.size) {
            val url = candidates[index]
            try {
                transfer(url, file, headers, progress)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                last = error
                if (error is CdnHttpException && (error.status == 403 || error.status == 412) &&
                    !refreshed && refreshCandidates != null) {
                    refreshed = true
                    val refreshedUrls = try {
                        refreshCandidates.invoke()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val fresh = refreshedUrls.filter { it.startsWith("https://") || it.startsWith("http://") }
                        .filterNot { it in candidates }
                    candidates.addAll(fresh)
                    if (fresh.isNotEmpty()) {
                        progress(file.length(), -1L)
                        index++
                        continue
                    }
                }
                if (index < candidates.lastIndex) {
                    file.delete()
                    File(file.parentFile, file.name + ".resume.json").delete()
                    progress(0L, -1L)
                }
            }
            index++
        }
        throw last ?: IllegalStateException("所有 CDN 地址均不可用")
    }

    private class CdnHttpException(val status: Int, val retryAfter: String?) : java.io.IOException("CDN 返回 HTTP " + status)

    private suspend fun transfer(url: String, file: File, headers: Map<String, String>,
        progress: (Long, Long) -> Unit) {
        var last: Exception? = null
        for (retry in 1..MAX_RETRIES) {
            try {
                transferOnce(url, file, headers, progress)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                last = error
                val waitMillis = when (error) {
                    is CdnHttpException -> if (error.status == 403 || error.status == 412) null
                        else BiliDownloadRetryPolicy.delayMillis(error.status, retry, error.retryAfter)
                    is java.net.SocketTimeoutException, is java.net.ConnectException,
                    is java.net.UnknownHostException, is java.net.SocketException, is java.io.EOFException ->
                        BiliDownloadRetryPolicy.delayMillis(null, retry, null)
                    else -> null
                } ?: break
                delay(waitMillis)
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    private suspend fun transferOnce(url: String, file: File, headers: Map<String, String>,
        progress: (Long, Long) -> Unit) {
        // BiliDownload's checkpoint rule: never append bytes across an unverified source.
        // Hash the complete signed URL so secrets are not written to disk.
        val metaFile = File(file.parentFile, file.name + ".resume.json")
        val sourceKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val savedMeta = runCatching { JSONObject(metaFile.readText()) }.getOrNull()
        fun strongEtag(value: String?): String? = value?.takeIf {
            it.length >= 2 && it.startsWith("\"") && it.endsWith("\"") &&
                !it.startsWith("W/", ignoreCase = true)
        }
        var offset = if (file.exists()) file.length() else 0L
        val savedEtag = strongEtag(savedMeta?.optString("etag"))
        if (offset > 0L &&
            (savedMeta?.optString("source") != sourceKey || savedEtag == null)) {
            RandomAccessFile(file, "rw").use { it.setLength(0L) }
            metaFile.delete()
            offset = 0L
        }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0L) {
                setRequestProperty("Range", "bytes=$offset-")
                setRequestProperty("If-Range", savedEtag!!)
            }
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
                    metaFile.delete()
                    return
                }
            }
            if (code != 200 && code != 206) throw CdnHttpException(code, c.getHeaderField("Retry-After"))
            val append = offset > 0L && code == 206
            var rangeTotal = -1L
            if (append) {
                val responseEtag = strongEtag(c.getHeaderField("ETag"))
                if (responseEtag == null || responseEtag != savedEtag) {
                    RandomAccessFile(file, "rw").use { it.setLength(0L) }
                    metaFile.delete()
                    error("CDN 未提供匹配的强 ETag，已清除旧分段以防止文件损坏")
                }
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
                    .matchEntire(c.getHeaderField("Content-Range").orEmpty())
                val rangeStart = range?.groupValues?.get(1)?.toLongOrNull()
                val rangeEnd = range?.groupValues?.get(2)?.toLongOrNull()
                rangeTotal = range?.groupValues?.get(3)?.takeIf { it != "*" }?.toLongOrNull() ?: -1L
                val length = c.getHeaderFieldLong("Content-Length", -1L)
                val rangeLength = if (rangeStart != null && rangeEnd != null) rangeEnd - rangeStart + 1L else -1L
                if (rangeStart != offset || rangeEnd == null || rangeEnd < offset ||
                    (rangeTotal >= 0L && rangeEnd >= rangeTotal) ||
                    (length >= 0L && rangeLength != length)) {
                    RandomAccessFile(file, "rw").use { it.setLength(0L) }
                    metaFile.delete()
                    error("服务器返回的续传范围与本地文件不匹配，已清除不可信临时片段")
                }
            }
            val start = if (append) offset else 0L
            val responseEtag = strongEtag(c.getHeaderField("ETag"))
            val len = c.getHeaderFieldLong("Content-Length", -1L)
            val total = if (rangeTotal > 0L) rangeTotal else if (len >= 0L) start + len else -1L
            // Metadata is written before body bytes. Weak/missing ETags intentionally disable
            // future append; a later retry safely restarts instead of joining unrelated CDN data.
            JSONObject().put("source", sourceKey)
                .put("etag", responseEtag ?: "")
                .put("total", total)
                .put("savedAt", System.currentTimeMillis())
                .let { metaFile.writeText(it.toString()) }
            RandomAccessFile(file, "rw").use { out ->
                if (!append) out.setLength(0L)
                out.seek(start)
                var done = start
                val input = c.inputStream
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    progress(done, total)
                }
                if (done == 0L || (total >= 0L && done != total)) {
                    error("资源长度校验失败：$done/$total")
                }
                metaFile.delete()
            }
        } finally {
            c.disconnect()
        }
    }


    /**
     * 合并 B站 DURL 的多个 MP4 分段。不能直接拼接 MP4 文件字节；
     * 这里按媒体轨道读取样本，并为每段增加前一段的时间偏移。
     */
    /** 使用 APK 内置媒体封装器合并 DURL MP4 分段。 */
    private suspend fun muxDurlSegments(segments: List<File>, output: File) {
        require(segments.isNotEmpty()) { "没有可合并的视频分段" }
        FfmpegMediaProcessor.mergeSegments(segments, output)
    }

    /** 使用 APK 内置媒体封装器将 DASH 音视频轨道合并为 MP4。 */
    private suspend fun muxDashTracks(video: File, audio: File, output: File) {
        require(video.isFile && video.length() > 0L) { "视频轨道文件不存在或为空" }
        require(audio.isFile && audio.length() > 0L) { "音频轨道文件不存在或为空" }
        FfmpegMediaProcessor.mergeDashTracks(video, audio, output)
    }

    /** 检查合并产物可重新解析，且音视频轨道的起点和时长没有明显错位。 */
    private data class TrackTiming(
        val mime: String, val durationUs: Long, val firstPtsUs: Long,
        val lastPtsUs: Long, val sampleCount: Long
    )

    /** 遍历轨道样本，检查时间戳单调性、有效样本和声明时长。 */
    private fun inspectTrack(file: File, trackIndex: Int): TrackTiming {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            extractor.selectTrack(trackIndex)
            var first = -1L
            var last = -1L
            var previous = Long.MIN_VALUE
            var count = 0L
            while (extractor.sampleTrackIndex >= 0) {
                val pts = extractor.sampleTime
                if (pts < 0L) error("媒体轨道包含无效时间戳：$mime")
                if (previous != Long.MIN_VALUE && pts < previous) error("媒体轨道时间戳不单调：$mime")
                if (first < 0L) first = pts
                last = pts
                previous = pts
                count++
                extractor.advance()
            }
            if (count == 0L || first < 0L || last < first) error("媒体轨道没有有效样本：$mime")
            if (duration > 0L && last - first > duration + maxOf(1500000L, duration / 20L)) {
                error("媒体样本超出轨道声明时长：$mime")
            }
            return TrackTiming(mime, duration, first, last, count)
        } finally { extractor.release() }
    }

    /** 校验音视频轨道存在性，并比较两条轨道的起点和结束位置。 */
    private fun validateMuxedOutput(file: File, requireAudio: Boolean) {
        if (!file.isFile || file.length() <= 1024L) error("合并输出文件为空或过小")
        val extractor = MediaExtractor()
        val tracks = mutableListOf<TrackTiming>()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    tracks += inspectTrack(file, index)
                }
            }
        } catch (error: Exception) {
            throw IllegalStateException("合并后媒体校验失败：" + (error.message ?: "无法读取输出文件"), error)
        } finally { extractor.release() }
        val video = tracks.firstOrNull { it.mime.startsWith("video/") } ?: error("合并文件缺少视频轨道")
        val audio = tracks.firstOrNull { it.mime.startsWith("audio/") }
        if (requireAudio && audio == null) error("合并文件缺少音频轨道")
        if (audio != null) {
            val startDelta = kotlin.math.abs(video.firstPtsUs - audio.firstPtsUs)
            if (startDelta > 750000L) error("音视频起始时间偏差过大：" + (startDelta / 1000) + " ms")
            val videoEnd = if (video.durationUs > 0L) video.firstPtsUs + video.durationUs else video.lastPtsUs
            val audioEnd = if (audio.durationUs > 0L) audio.firstPtsUs + audio.durationUs else audio.lastPtsUs
            val endDelta = kotlin.math.abs(videoEnd - audioEnd)
            val referenceDuration = listOf(video.durationUs, audio.durationUs).filter { it > 0L }.minOrNull() ?: 0L
            val tolerance = maxOf(2000000L, referenceDuration / 30L)
            if (endDelta > tolerance) error("音视频结束位置不匹配：" + (endDelta / 1000) + " ms")
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

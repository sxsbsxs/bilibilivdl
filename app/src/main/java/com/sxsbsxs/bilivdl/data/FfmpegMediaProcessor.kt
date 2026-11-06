package com.sxsbsxs.bilivdl.data

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Native FFmpegKit muxing. The AAR is bundled into the APK. */
object FfmpegMediaProcessor {
    private val executionMutex = Mutex()

    suspend fun mergeDashTracks(video: File, audio: File, output: File) {
        execute(listOf("-y", "-i", video.absolutePath, "-i", audio.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
            "-fflags", "+genpts", "-avoid_negative_ts", "make_zero",
            "-movflags", "+faststart", output.absolutePath), output)
    }

    suspend fun mergeSegments(segments: List<File>, output: File) {
        require(segments.isNotEmpty()) { "没有可合并的视频分段" }
        val manifest = File(output.parentFile, output.nameWithoutExtension + ".concat.txt")
        try {
            val lines = segments.map { segment ->
                require(segment.isFile && segment.length() > 0L) { "视频分段不存在或为空" }
                "file '${segment.absolutePath}'"
            }
            manifest.writeText(lines.joinToString("\n", postfix = "\n"))
            execute(listOf("-y", "-f", "concat", "-safe", "0", "-i", manifest.absolutePath,
                "-map", "0", "-c", "copy", "-fflags", "+genpts",
                "-avoid_negative_ts", "make_zero", "-movflags", "+faststart",
                output.absolutePath), output)
        } finally { manifest.delete() }
    }

    private suspend fun execute(arguments: List<String>, output: File) = executionMutex.withLock {
        withContext(Dispatchers.IO) {
            output.delete()
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) runCatching { FFmpegKit.cancel() }
            }
            val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
            currentCoroutineContext().ensureActive()
            if (!ReturnCode.isSuccess(session.returnCode)) {
                output.delete()
                val detail = session.allLogsAsString?.takeLast(1600).orEmpty()
                throw IllegalStateException("FFmpeg 合并失败：" + detail.ifBlank { session.failStackTrace.orEmpty() })
            }
            require(output.isFile && output.length() > 0L) { "FFmpeg 没有生成有效输出文件" }
        }
    }
}
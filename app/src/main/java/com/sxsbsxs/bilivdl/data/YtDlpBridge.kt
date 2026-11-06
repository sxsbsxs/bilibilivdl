package com.sxsbsxs.bilivdl.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Generic extractor fallback powered by yt-dlp embedded through Chaquopy.
 * Bilibili's native API/downloader remains the preferred path for DASH quality.
 */
object YtDlpBridge {
    private val pythonRuntimeLock = Any()

    suspend fun download(
        context: Context,
        url: String,
        cookie: String = ""
    ): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) {
            "请输入有效的视频网址"
        }

        // Bilibili URLs use the native resolver/downloader so 403 recovery can refresh
        // playurl through the same application-layer API as ordinary download tasks.
        val biliId = BiliApi.resolveVideoId(url)
        if (biliId != null) {
            val cookieValue = BiliSession.cookie(context)
            val aidValue = biliId.removePrefix("av").toLongOrNull() ?: 0L
            val bvidValue = if (biliId.startsWith("BV", ignoreCase = true)) biliId else ""
            val accountProfile = if (cookieValue.isNotBlank()) {
                runCatching { BiliSession.profile(context, cookieValue) }.getOrNull()
            } else null
            val vip = (accountProfile?.optInt("vipType") ?: 0) > 0
            val loggedIn = accountProfile?.optBoolean("isLogin") == true
            suspend fun resolveBiliPlayback(): PlaybackInfo =
                BiliPlaybackApi.load(bvidValue, aidValue, 127, cookieValue, isVip = vip, isLoggedIn = loggedIn)
            val playback = resolveBiliPlayback()
            return@withContext BiliDownloadEngine.download(
                context, playback, playback.title.ifBlank { "B站视频" }, cookieValue,
                taskId = "ytdlp-bili-" + biliId.hashCode(),
                refreshPlayback = { resolveBiliPlayback() }
            ) { _, _, _ -> }
        }

        val outputDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir,
            "BiliVdl"
        ).apply { mkdirs() }
        val path = synchronized(pythonRuntimeLock) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            Python.getInstance().getModule("ytdlp_bridge")
                .callAttr("download", url, outputDir.absolutePath, cookie).toString()
        }
        val downloaded = File(path).canonicalFile
        val safeRoot = outputDir.canonicalFile
        val safePrefix = safeRoot.absolutePath.trimEnd(File.separatorChar) + File.separator
        require(downloaded.absolutePath == safeRoot.absolutePath || downloaded.absolutePath.startsWith(safePrefix)) {
            "yt-dlp 返回的文件路径超出了应用下载目录"
        }
        require(downloaded.isFile && downloaded.length() > 0L) {
            "yt-dlp 没有生成有效的视频文件"
        }
        publish(context.applicationContext, downloaded)
    }

    private fun publish(context: Context, file: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return file.absolutePath
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BiliVdl")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("无法创建系统视频文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入系统视频文件")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            return uri.toString()
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}

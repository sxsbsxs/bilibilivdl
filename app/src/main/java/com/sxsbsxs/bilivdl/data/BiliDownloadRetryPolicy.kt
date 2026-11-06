package com.sxsbsxs.bilivdl.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Bounded CDN retry policy adapted from BiliDownload's DownloadRetryPolicy.
 * Honors Retry-After and stops automatic retries rather than retrying early
 * when the server requests a wait longer than one minute.
 */
internal object BiliDownloadRetryPolicy {
    const val MAX_RETRIES = 5
    private const val MAX_SERVER_WAIT_MS = 60_000L

    fun delayMillis(httpStatus: Int?, retry: Int, retryAfter: String?): Long? {
        if (retry !in 1..MAX_RETRIES) return null
        if (httpStatus != null && httpStatus !in RETRYABLE_HTTP) return null
        val serverWait = parseRetryAfter(retryAfter)
        if (serverWait > MAX_SERVER_WAIT_MS) return null
        val backoff = (1000L shl (retry - 1).coerceAtMost(4)) + Random.nextLong(251)
        return maxOf(backoff, serverWait)
    }

    private fun parseRetryAfter(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        if (text.matches(Regex("[0-9]+"))) {
            val seconds = text.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
        }
        val date = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }.parse(text)
        }.getOrNull() ?: return 0L
        return (date.time - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private val RETRYABLE_HTTP = setOf(408, 425, 429, 500, 502, 503, 504)
}

package com.sxsbsxs.bilivdl.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sxsbsxs.bilivdl.data.BiliAccount
import com.sxsbsxs.bilivdl.data.BiliQrLoginApi
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrLoginActivity : AppCompatActivity() {
    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private var qrUrl by mutableStateOf<String?>(null)
    private var statusMessage by mutableStateOf("正在获取二维码…")
    private var loading by mutableStateOf(true)
    private var scanned by mutableStateOf(false)
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                BiliQrLoginScreen(
                    qrBitmap = qrBitmap,
                    qrUrl = qrUrl,
                    statusMessage = statusMessage,
                    loading = loading,
                    scanned = scanned,
                    onRefresh = ::startLoginFlow,
                    onOpenQrUrl = ::openQrUrl,
                    onBack = ::finish
                )
            }
        })
        startLoginFlow()
    }

    private fun startLoginFlow() {
        loginJob?.cancel()
        qrBitmap = null
        qrUrl = null
        scanned = false
        loading = true
        statusMessage = "正在获取二维码…"
        loginJob = lifecycleScope.launch {
            try {
                // BiliDownload keeps one QR transaction alive and requests a new QR
                // when the 180-second key expires. Keep that behavior without a
                // fixed retry limit so the screen never gets stranded on an old QR.
                while (isActive) {
                    val qr = try {
                        withContext(Dispatchers.IO) { BiliQrLoginApi.generate() }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        loading = false
                        statusMessage = "二维码获取失败：${error.message ?: "网络异常"}，正在重试…"
                        delay(2500)
                        loading = true
                        continue
                    }

                    qrUrl = qr.url
                    qrBitmap = withContext(Dispatchers.Default) { createQrBitmap(qr.url, 720) }
                    scanned = false
                    loading = false
                    statusMessage = "请打开哔哩哔哩 APP 扫描二维码"
                    var elapsed = 0

                    while (isActive && elapsed < 180) {
                        delay(1000)
                        elapsed++

                        val result = try {
                            withContext(Dispatchers.IO) { BiliQrLoginApi.poll(qr.key) }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            statusMessage = "网络波动，正在继续检查登录状态…"
                            // A temporary poll failure must not destroy a valid QR session.
                            delay(1200)
                            continue
                        }

                        when (result.code) {
                            0 -> {
                                if (!BiliSession.hasRequiredCookies(result.cookie)) {
                                    statusMessage = "扫码已确认，但登录凭据不完整，请重新获取二维码"
                                    return@launch
                                }

                                scanned = true
                                statusMessage = "已扫码，正在验证账号授权…"
                                var account: BiliAccount? = null
                                var validationError: Exception? = null
                                repeat(3) {
                                    if (account == null) {
                                        try {
                                            account = withContext(Dispatchers.IO) {
                                                BiliSession.account(applicationContext, result.cookie)
                                            }
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            validationError = error
                                        }
                                        if (account == null) delay(1200)
                                    }
                                }

                                val verifiedAccount = account
                                if (verifiedAccount == null) {
                                    statusMessage = "账号验证失败：${validationError?.message ?: "请检查网络后重试"}"
                                    return@launch
                                }
                                if (!verifiedAccount.isLogin) {
                                    statusMessage = "B站尚未确认登录状态，请重新扫码"
                                    return@launch
                                }

                                // Save only after /nav confirms the authenticated account.
                                BiliSession.saveCookie(applicationContext, result.cookie)
                                statusMessage = "登录成功：${verifiedAccount.username}"
                                delay(650)
                                setResult(RESULT_OK)
                                finish()
                                return@launch
                            }
                            86101 -> {
                                scanned = false
                                statusMessage = "等待使用 B站 APP 扫描二维码…"
                            }
                            86090 -> {
                                scanned = true
                                statusMessage = "已扫码，请在 B站 APP 中选择账号并确认授权"
                            }
                            86038 -> {
                                statusMessage = "二维码已过期，正在自动刷新…"
                                break
                            }
                            else -> {
                                statusMessage = result.message.ifBlank { "正在等待扫码…" }
                            }
                        }
                    }

                    if (!isActive) return@launch
                    // QR keys expire after 180 seconds. Automatically replace them,
                    // matching BiliDownload's continuously-running QR business loop.
                    loading = true
                    scanned = false
                    statusMessage = "二维码已失效，正在获取新二维码…"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isActive) {
                    loading = false
                    statusMessage = "登录失败：${error.message ?: "未知错误"}"
                }
            } finally {
                if (isActive) loading = false
            }
        }
    }

    private fun openQrUrl() {
        val url = qrUrl ?: return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { statusMessage = "无法打开登录链接，请使用 B站 APP 扫描二维码" }
    }

    private fun createQrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    override fun onDestroy() {
        loginJob?.cancel()
        super.onDestroy()
    }
}

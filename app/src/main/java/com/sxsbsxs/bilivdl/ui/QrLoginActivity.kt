package com.sxsbsxs.bilivdl.ui

import android.graphics.Bitmap
import android.graphics.Color
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
    private var statusMessage by mutableStateOf("正在获取二维码…")
    private var loading by mutableStateOf(true)
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    BiliQrLoginScreen(
                        qrBitmap = qrBitmap,
                        statusMessage = statusMessage,
                        loading = loading,
                        onRefresh = ::startLoginFlow,
                        onBack = ::finish
                    )
                }
            }
        )
        startLoginFlow()
    }

    private fun startLoginFlow() {
        loginJob?.cancel()
        qrBitmap = null
        loading = true
        statusMessage = "正在获取二维码…"
        loginJob = lifecycleScope.launch {
            try {
                val qr = withContext(Dispatchers.IO) { BiliQrLoginApi.generate() }
                qrBitmap = withContext(Dispatchers.Default) { createQrBitmap(qr.url, 720) }
                loading = false
                statusMessage = "请打开哔哩哔哩 APP，扫描上方二维码"

                var elapsed = 0
                while (isActive && elapsed < 180) {
                    delay(1000)
                    elapsed++
                    val result = withContext(Dispatchers.IO) { BiliQrLoginApi.poll(qr.key) }
                    when (result.code) {
                        0 -> {
                            if (!result.cookie.contains("SESSDATA=") ||
                                !result.cookie.contains("DedeUserID=") ||
                                !result.cookie.contains("bili_jct=")
                            ) {
                                statusMessage = "扫码已确认，但登录凭据不完整，请刷新二维码重试"
                                return@launch
                            }
                            statusMessage = "登录成功，正在验证账号…"
                            val profile = withContext(Dispatchers.IO) {
                                BiliSession.profile(applicationContext, result.cookie)
                            }
                            if (!profile.optBoolean("isLogin")) {
                                statusMessage = "B站暂未确认登录状态，请刷新二维码重试"
                                return@launch
                            }
                            BiliSession.saveCookie(applicationContext, result.cookie)
                            statusMessage = "登录成功：${profile.optString("uname", "B站用户")}"
                            delay(650)
                            setResult(RESULT_OK)
                            finish()
                            return@launch
                        }
                        86101 -> statusMessage = "等待使用 B站 APP 扫描二维码…"
                        86090 -> statusMessage = "已扫码，请在 B站 APP 中确认登录"
                        86038 -> {
                            statusMessage = "二维码已过期，正在刷新…"
                            startLoginFlow()
                            return@launch
                        }
                        else -> statusMessage = result.message.ifBlank { "正在等待扫码…" }
                    }
                }
                if (isActive) statusMessage = "二维码已超时，请点击刷新二维码"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) statusMessage = "登录失败：${e.message ?: "网络异常"}"
            } finally {
                if (isActive) loading = false
            }
        }
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

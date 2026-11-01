package com.sxsbsxs.bilivdl.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliQrLoginApi
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QrLoginActivity : AppCompatActivity() {
    private lateinit var qrImage: ImageView
    private lateinit var status: TextView
    private lateinit var refresh: MaterialButton
    private var loginJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_login)
        qrImage = findViewById(R.id.qr_image)
        status = findViewById(R.id.qr_status)
        refresh = findViewById(R.id.qr_refresh)
        findViewById<android.view.View>(R.id.qr_back).setOnClickListener { finish() }
        refresh.setOnClickListener { startLoginFlow() }
        startLoginFlow()
    }

    private fun startLoginFlow() {
        loginJob?.cancel()
        qrImage.setImageDrawable(null)
        refresh.isEnabled = false
        status.text = "正在获取二维码…"
        loginJob = lifecycleScope.launch {
            try {
                val qr = withContext(Dispatchers.IO) { BiliQrLoginApi.generate() }
                qrImage.setImageBitmap(createQrBitmap(qr.url, 720))
                status.text = "请打开哔哩哔哩 APP，扫描上方二维码"
                refresh.isEnabled = true
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
                                status.text = "扫码已确认，但登录凭据不完整，请刷新二维码重试"
                                return@launch
                            }
                            status.text = "登录成功，正在验证账号…"
                            val profile = withContext(Dispatchers.IO) {
                                BiliSession.profile(applicationContext, result.cookie)
                            }
                            if (!profile.optBoolean("isLogin")) {
                                status.text = "B站暂未确认登录状态，请刷新二维码重试"
                                return@launch
                            }
                            BiliSession.saveCookie(applicationContext, result.cookie)
                            status.text = "登录成功：${profile.optString("uname", "B站用户")}"
                            delay(650)
                            setResult(RESULT_OK)
                            finish()
                            return@launch
                        }
                        86101 -> status.text = "等待使用 B站 APP 扫描二维码…"
                        86090 -> status.text = "已扫码，请在 B站 APP 中确认登录"
                        86038 -> {
                            status.text = "二维码已过期，正在刷新…"
                            startLoginFlow()
                            return@launch
                        }
                        else -> status.text = result.message.ifBlank { "正在等待扫码…" }
                    }
                }
                if (isActive) status.text = "二维码已超时，请点击刷新二维码"
            } catch (e: Exception) {
                if (isActive) status.text = "登录失败：${e.message ?: "网络异常"}"
            } finally {
                refresh.isEnabled = true
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

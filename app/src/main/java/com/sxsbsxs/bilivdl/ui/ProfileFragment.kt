package com.sxsbsxs.bilivdl.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliQrLoginApi
import com.sxsbsxs.bilivdl.data.BiliSession
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var status: TextView? = null
    private var accountName: TextView? = null
    private var qrJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.account_status)
        accountName = view.findViewById(R.id.account_name)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.account_login)
            .setOnClickListener { openQrLogin() }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.account_refresh)
            .setOnClickListener { refresh() }
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.account_logout)
            .setOnClickListener {
                BiliSession.clear(requireContext())
                accountName?.text = "未登录"
                status?.text = "登录凭据已清除"
            }
        refresh()
    }

    /** BVD-style QR flow: generate a QR key, poll its state, then verify the received session. */
    private fun openQrLogin() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val qrImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "哔哩哔哩登录二维码"
        }
        val message = TextView(context).apply {
            text = "正在获取登录二维码…"
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(0, dp(12), 0, dp(8))
        }
        content.addView(qrImage, LinearLayout.LayoutParams(dp(248), dp(248)))
        content.addView(message, LinearLayout.LayoutParams(-1, -2))

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("扫码登录")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("刷新二维码", null)
            .create()

        fun startQrFlow() {
            qrJob?.cancel()
            qrImage.setImageDrawable(null)
            message.text = "正在获取登录二维码…"
            qrJob = viewLifecycleOwner.lifecycleScope.launch {
                try {
                    while (isActive && dialog.isShowing) {
                        val qr = withContext(Dispatchers.IO) { BiliQrLoginApi.generate() }
                        qrImage.setImageBitmap(createQrBitmap(qr.url, 640))
                        message.text = "请使用哔哩哔哩 APP 扫描二维码"

                        var expired = false
                        var elapsed = 0
                        while (isActive && dialog.isShowing && elapsed < 180) {
                            // Match BVD's one-second QR status polling cadence.
                            delay(1000)
                            elapsed += 1
                            val result = withContext(Dispatchers.IO) { BiliQrLoginApi.poll(qr.key) }
                            when (result.code) {
                                0 -> {
                                    if (result.cookie.isBlank() ||
                                        !result.cookie.contains("SESSDATA=") ||
                                        !result.cookie.contains("DedeUserID=")
                                    ) {
                                        message.text = "扫码成功，但登录凭据不完整，请刷新二维码重试"
                                        expired = true
                                        break
                                    }
                                    message.text = "登录成功，正在验证账号…"
                                    // Validate the temporary credentials before persisting them.
                                    val profile = withContext(Dispatchers.IO) {
                                        BiliSession.profile(context, result.cookie)
                                    }
                                    if (!profile.optBoolean("isLogin")) {
                                        message.text = "B站未确认登录状态，请刷新二维码重试"
                                        expired = true
                                        break
                                    }
                                    BiliSession.saveCookie(context, result.cookie)
                                    accountName?.text = profile.optString("uname", "已登录")
                                    status?.text = "账号已连接 · UID ${profile.optLong("mid")}"
                                    dialog.dismiss()
                                    return@launch
                                }
                                86101 -> message.text = "等待使用 B 站 APP 扫码…"
                                86090 -> message.text = "已扫码，请在哔哩哔哩 APP 中确认登录"
                                86038 -> {
                                    message.text = "二维码已过期，正在刷新…"
                                    expired = true
                                    break
                                }
                                else -> message.text = result.message.ifBlank { "登录状态获取失败" }
                            }
                        }
                        if (!dialog.isShowing || !isActive) break
                        if (expired || elapsed >= 180) {
                            message.text = "正在刷新二维码…"
                            continue
                        }
                        break
                    }
                } catch (e: Exception) {
                    if (dialog.isShowing) {
                        message.text = "登录失败：${e.message ?: "网络异常"}"
                    }
                }
            }
        }

        dialog.setOnDismissListener { qrJob?.cancel() }
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener { startQrFlow() }
        startQrFlow()
    }

    private fun createQrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun refresh() {
        status?.text = "正在验证账号状态…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext().applicationContext
                val data = withContext(Dispatchers.IO) { BiliSession.profile(context) }
                val logged = data.optBoolean("isLogin")
                accountName?.text = if (logged) data.optString("uname", "已登录") else "未登录"
                status?.text = if (logged) {
                    "账号已连接 · UID ${data.optLong("mid")}"
                } else {
                    "未检测到有效登录。点击下方按钮扫码登录。"
                }
            } catch (e: Exception) {
                accountName?.text = if (BiliSession.cookie(requireContext()).isBlank()) "未登录" else "待验证"
                status?.text = "账号状态获取失败：${e.message ?: "网络异常"}"
            }
        }
    }

    override fun onDestroyView() {
        qrJob?.cancel()
        qrJob = null
        status = null
        accountName = null
        super.onDestroyView()
    }
}
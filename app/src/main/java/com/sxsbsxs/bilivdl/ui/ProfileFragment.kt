package com.sxsbsxs.bilivdl.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileFragment : Fragment(R.layout.fragment_profile) {
    private var status: TextView? = null
    private var accountName: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.account_status)
        accountName = view.findViewById(R.id.account_name)
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.account_login)
            .setOnClickListener { openBiliLogin() }
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

    /**
     * BVD-style WebView login handoff: wait for the mobile Bilibili site to
     * redirect after authentication, then validate the captured cookie with /nav.
     * A page load alone is never treated as a successful login.
     */
    private fun openBiliLogin() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        val webView = WebView(context)
        container.addView(progress, LinearLayout.LayoutParams(-1, (4 * density).toInt()))
        container.addView(webView, LinearLayout.LayoutParams(-1, (560 * density).toInt()))

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("登录哔哩哔哩")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()

        var handingOff = false
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.loadsImagesAutomatically = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        fun captureAndValidate() {
            if (handingOff || !dialog.isShowing) return
            val cookie = listOf(
                "https://bilibili.com",
                "https://www.bilibili.com",
                "https://m.bilibili.com"
            ).asSequence().mapNotNull { cookies.getCookie(it) }
                .firstOrNull { it.contains("SESSDATA=") && it.contains("DedeUserID=") }
                .orEmpty()
            if (cookie.isBlank()) return

            handingOff = true
            progress.visibility = View.VISIBLE
            progress.isIndeterminate = true
            BiliSession.saveCookie(context, cookie)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val profile = withContext(Dispatchers.IO) { BiliSession.profile(context) }
                    if (!profile.optBoolean("isLogin")) {
                        handingOff = false
                        status?.text = "检测到登录凭据，但 B 站尚未确认登录成功"
                        return@launch
                    }
                    accountName?.text = profile.optString("uname", "已登录")
                    status?.text = "账号已连接 · UID ${profile.optLong("mid")}"
                    webView.stopLoading()
                    dialog.dismiss()
                } catch (e: Exception) {
                    handingOff = false
                    status?.text = "登录验证失败：${e.message ?: "网络异常"}"
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty().lowercase()
                if (host == "m.bilibili.com" || host == "www.bilibili.com" || host == "bilibili.com") {
                    captureAndValidate()
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!progress.isIndeterminate) progress.visibility = View.GONE
                val host = runCatching { android.net.Uri.parse(url).host.orEmpty() }.getOrDefault("")
                if (host == "m.bilibili.com" || host == "www.bilibili.com" || host == "bilibili.com") {
                    captureAndValidate()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (!progress.isIndeterminate) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }
        }
        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        dialog.show()
        status?.text = "请在打开的 B 站页面完成登录"
        webView.loadUrl("https://passport.bilibili.com/h5-app/passport/login")
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
                    "未检测到有效登录。点击下方按钮使用 B 站登录页面登录。"
                }
            } catch (e: Exception) {
                accountName?.text = if (BiliSession.cookie(requireContext()).isBlank()) "未登录" else "待验证"
                status?.text = "账号状态获取失败：${e.message ?: "网络异常"}"
            }
        }
    }

    override fun onDestroyView() {
        status = null
        accountName = null
        super.onDestroyView()
    }
}
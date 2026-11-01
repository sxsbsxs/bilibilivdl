package com.sxsbsxs.bilivdl.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sxsbsxs.bilivdl.R
import com.sxsbsxs.bilivdl.data.BiliSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BVD 同款移动网页登录：使用 B 站官方移动站点完成登录，
 * 只在回到 m.bilibili.com 且 nav 接口确认 isLogin 后保存 Cookie。
 */
class WebLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var validating = false
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_login)
        webView = findViewById(R.id.login_web)
        progress = findViewById(R.id.login_progress)
        status = findViewById(R.id.login_status)
        findViewById<View>(R.id.login_back).setOnClickListener { finish() }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.loadsImagesAutomatically = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                if (host == "m.bilibili.com") {
                    validateMobileLogin()
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.startsWith("https://m.bilibili.com/")) validateMobileLogin()
            }
        }
        status.text = "正在打开 B 站官方登录页面…"
        webView.loadUrl("https://passport.bilibili.com/h5-app/passport/login")
    }

    private fun validateMobileLogin() {
        if (validating || finished) return
        val cookies = CookieManager.getInstance().getCookie("https://bilibili.com").orEmpty()
        if (!cookies.contains("SESSDATA=") || !cookies.contains("DedeUserID=")) {
            status.text = "请在 B 站页面完成登录"
            return
        }
        validating = true
        status.text = "已获取登录状态，正在验证账号…"
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    BiliSession.profile(applicationContext, cookies)
                }
                if (!profile.optBoolean("isLogin")) {
                    status.text = "尚未完成登录，请继续在网页中操作"
                    validating = false
                    return@launch
                }
                BiliSession.saveCookie(applicationContext, cookies)
                finished = true
                status.text = "登录成功：${profile.optString("uname", "B站用户")}"
                setResult(RESULT_OK)
                webView.postDelayed({ if (!isFinishing) finish() }, 650)
            } catch (e: Exception) {
                status.text = "账号验证失败：${e.message ?: "请稍后重试"}"
                validating = false
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}

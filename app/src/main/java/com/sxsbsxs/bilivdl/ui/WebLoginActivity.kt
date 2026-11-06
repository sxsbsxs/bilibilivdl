package com.sxsbsxs.bilivdl.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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

class WebLoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var validating = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_login)
        web = findViewById(R.id.login_web)
        progress = findViewById(R.id.login_progress)
        status = findViewById(R.id.login_status)
        findViewById<View>(R.id.login_back).setOnClickListener { finish() }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        web.settings.loadsImagesAutomatically = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                inspectLoginCookies()
            }
        }
        web.loadUrl("https://passport.bilibili.com/h5-app/passport/login")
    }

    private fun inspectLoginCookies() {
        if (validating) return
        val manager = CookieManager.getInstance()
        val domains = listOf("https://passport.bilibili.com", "https://www.bilibili.com", "https://m.bilibili.com", "https://bilibili.com")
        val cookies = domains.flatMap { manager.getCookie(it).orEmpty().split(";").map(String::trim) }
            .filter { it.contains("=") }.distinctBy { it.substringBefore("=") }
        val cookie = cookies.joinToString("; ")
        if (!cookie.contains("SESSDATA=") || !cookie.contains("DedeUserID=") || !cookie.contains("bili_jct=")) return
        validating = true
        status.text = "正在验证登录状态…"
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { BiliSession.profile(applicationContext, cookie) }
                if (!profile.optBoolean("isLogin")) error("B站尚未确认登录，请继续完成页面验证")
                BiliSession.saveCookie(applicationContext, cookie)
                status.text = "登录成功：" + profile.optString("uname", "B站用户")
                setResult(RESULT_OK)
                finish()
            } catch (error: Exception) {
                status.text = error.message ?: "登录验证失败"
                validating = false
            }
        }
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}

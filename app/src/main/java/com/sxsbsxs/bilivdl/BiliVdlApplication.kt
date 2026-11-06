package com.sxsbsxs.bilivdl

import android.app.Application
import android.webkit.CookieManager
import cc.kafuu.bilidownload.common.CommonLibs
import cc.kafuu.bilidownload.common.manager.AccountManager
import com.chibatching.kotpref.Kotpref
import com.a10miaomiao.bilimiao.comm.BilimiaoCommCore
import com.a10miaomiao.bilimiao.comm.platform.AndroidBase64Provider
import com.a10miaomiao.bilimiao.comm.platform.AndroidCookieProvider
import com.a10miaomiao.bilimiao.comm.platform.AndroidDeviceInfoProvider
import com.a10miaomiao.bilimiao.comm.platform.AndroidPlatformContext
import com.a10miaomiao.bilimiao.comm.platform.PlatformProviders
import com.sxsbsxs.bilivdl.data.BiliSession
import com.sxsbsxs.bilivdl.data.queue.DownloadQueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Bridges the existing BiliVdl account cookie into bilimiao's shared network core.
 * The UI remains BiliVdl-owned; upstream APIs and playback/download libraries are reused.
 */
class BiliVdlApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()

        PlatformProviders.context = AndroidPlatformContext(this)
        PlatformProviders.cookieProvider = AndroidCookieProvider()
        PlatformProviders.deviceInfo = AndroidDeviceInfoProvider(this)
        PlatformProviders.base64 = AndroidBase64Provider()

        BilimiaoCommCore().onCreate()

        Kotpref.init(this)
        CommonLibs.init(this)

        startupScope.launch { runCatching { DownloadQueueStore.migrateLegacy(this@BiliVdlApplication) } }

        val cookie = BiliSession.cookie(this)
        if (cookie.isNotBlank()) {
            AccountManager.updateCookie(cookie)
            val manager = CookieManager.getInstance()
            listOf(
                "https://bilibili.com",
                "https://www.bilibili.com",
                "https://api.bilibili.com",
                "https://passport.bilibili.com",
                "https://m.bilibili.com"
            ).forEach { manager.setCookie(it, cookie) }
            manager.flush()
        }
    }
}

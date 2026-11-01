package cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin

import android.webkit.CookieManager
import androidx.lifecycle.viewModelScope
import cc.kafuu.bilidownload.common.core.compose.CoreCompViewModelWithEvent
import cc.kafuu.bilidownload.common.core.compose.UiIntentObserver
import cc.kafuu.bilidownload.common.jni.QrCodeNativeLib
import cc.kafuu.bilidownload.common.manager.AccountManager
import cc.kafuu.bilidownload.common.network.IServerCallback
import cc.kafuu.bilidownload.common.network.NetworkConfig
import cc.kafuu.bilidownload.common.network.manager.NetworkManager
import cc.kafuu.bilidownload.common.network.model.BiliQrCodeData
import cc.kafuu.bilidownload.common.network.model.BiliQrCodePollData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class QrLoginViewModel :
    CoreCompViewModelWithEvent<QrLoginUiIntent, QrLoginUiState, QrLoginUiEvent>(QrLoginUiState.None) {

    private var mQrBusinessJob: Job? = null

    init {
        AccountManager.accountLiveData.observeForeverAutoRemove {
            if (it != null) QrLoginUiState.Finished.setup()
        }
    }

    /**
     * 请求二维码
     */
    private suspend fun generateQrCode() = suspendCancellableCoroutine { co ->
        val callback = object : IServerCallback<BiliQrCodeData> {
            override fun onSuccess(
                httpCode: Int,
                code: Int,
                message: String,
                data: BiliQrCodeData
            ) {
                if (co.isActive) co.resume(Result.success(data))
            }

            override fun onFailure(httpCode: Int, code: Int, message: String) {
                if (co.isActive) co.resume(Result.failure(IllegalStateException(message)))
            }
        }
        NetworkManager.biliAccountRepository.generateQrCode(callback)
    }

    /**
     * 轮询二维码
     */
    private suspend fun pollQrCode(key: String) = suspendCancellableCoroutine { co ->
        val callback = object : IServerCallback<Pair<BiliQrCodePollData, String>> {
            override fun onSuccess(
                httpCode: Int,
                code: Int,
                message: String,
                data: Pair<BiliQrCodePollData, String>
            ) {
                if (co.isActive) co.resume(Result.success(data))
            }

            override fun onFailure(httpCode: Int, code: Int, message: String) {
                if (co.isActive) co.resume(Result.failure(IllegalStateException(message)))
            }
        }
        NetworkManager.biliAccountRepository.pollQrCode(key, callback)
    }

    /**
     * 二维码登录流程事务
     */
    private suspend fun doCheckQrCode() {
        when (val userState = getOrNull<QrLoginUiState.Normal>()?.userState) {
            null, QrLoginUserState.QrRequestFailed -> Unit
            // 当前状态为正在请求二维码，则执行二维码请求逻辑
            QrLoginUserState.QrRequesting -> doRequestQrCode()
            // 当前状态为等待用户登录
            is QrLoginUserState.WaitingScanning -> doPollQrCode(userState.qrKey)
            // 等待用户确认
            is QrLoginUserState.WaitingConfirmation -> doPollQrCode(userState.qrKey)
            // 登录成功
            is QrLoginUserState.Completed -> doCompleted(userState.cookies)
        }
    }

    /**
     * 拉取新二维码
     */
    private suspend fun doRequestQrCode() {
        val userState = generateQrCode().getOrNull()?.let {
            QrLoginUserState.WaitingScanning(
                qrUrl = it.url,
                qrBitmap = QrCodeNativeLib.generateQrBitmap(it.url),
                qrKey = it.qrcodeKey
            )
        } ?: QrLoginUserState.QrRequestFailed

        awaitStateOf<QrLoginUiState.Normal>().copy(userState = userState).setup()
    }

    /**
     * 二维码轮寻
     */
    private suspend fun doPollQrCode(key: String) {
        val pollData = pollQrCode(key).getOrNull() ?: return
        when (pollData.first.code) {
            // 扫码登录成功
            0 -> QrLoginUserState.Completed(pollData.second)
            // 二维码已失效（状态切换为刷新状态准备再次请求二维码）
            86038 -> QrLoginUserState.QrRequesting
            // 其它状态
            else -> null
        }?.run {
            awaitStateOf<QrLoginUiState.Normal>().copy(userState = this).setup()
        }
    }

    /**
     * 二维码登录成功
     */
    private fun doCompleted(cookies: String) {
        CookieManager.getInstance().setCookie(NetworkConfig.BILI_MAIN_URL, cookies)
        AccountManager.updateCookie(cookies)
    }

    /**
     * 启动二维码事务循环
     */
    private fun startQrBusinessLoop() {
        if (mQrBusinessJob?.isActive == true) mQrBusinessJob?.cancel()
        mQrBusinessJob = viewModelScope.launch {
            while (true) {
                doCheckQrCode()
                delay(1000)
            }
        }
    }

    @UiIntentObserver(QrLoginUiIntent.CreateQr::class)
    fun onCreateOr() {
        if (!isStateOf<QrLoginUiState.None>()) return
        QrLoginUiState.Normal().setup()
        startQrBusinessLoop()
    }

    @UiIntentObserver(QrLoginUiIntent.TryBack::class)
    fun onTryBack() {
        QrLoginUiState.Finished.setup()
    }

    @UiIntentObserver(QrLoginUiIntent.ClickQrImage::class)
    fun onClickQrImage() {
        getOrNull<QrLoginUiState.Normal>()
            ?.copy(userState = QrLoginUserState.QrRequesting)
            ?.setup()
    }

    @UiIntentObserver(QrLoginUiIntent.SwitchToPasswordLogin::class)
    fun onSwitchToPasswordLogin() = viewModelScope.launch {
        QrLoginUiEvent.JumpToPasswordLogin.awaitSend()
    }
}
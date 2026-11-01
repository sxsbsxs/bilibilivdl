package cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin

import android.graphics.Bitmap

sealed class QrLoginUiState {
    data object None : QrLoginUiState()
    data class Normal(
        val userState: QrLoginUserState = QrLoginUserState.QrRequesting,
    ) : QrLoginUiState()
    data object Finished : QrLoginUiState()
}

sealed class QrLoginUserState {
    /**
     * 正在请求二维码
     */
    data object QrRequesting : QrLoginUserState()

    /**
     * 二维码请求失败
     */
    data object QrRequestFailed : QrLoginUserState()

    /**
     * 等待用户扫码
     */
    data class WaitingScanning(
        val qrUrl: String,
        val qrBitmap: Bitmap,
        val qrKey: String,
        val createTime: Long = System.currentTimeMillis(),
    ) : QrLoginUserState()

    /**
     * 等待用户确认
     */
    data class WaitingConfirmation(
        val qrKey: String,
        val message: String
    ) : QrLoginUserState()

    /**
     * 确认完成
     */
    data class Completed(
        val cookies: String
    ) : QrLoginUserState()
}
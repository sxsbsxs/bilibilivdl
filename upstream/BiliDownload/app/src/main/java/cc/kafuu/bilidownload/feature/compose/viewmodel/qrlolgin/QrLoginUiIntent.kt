package cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin

sealed class QrLoginUiIntent {
    data object CreateQr : QrLoginUiIntent()
    data object TryBack : QrLoginUiIntent()
    data object ClickQrImage : QrLoginUiIntent()
    data object SwitchToPasswordLogin : QrLoginUiIntent()
}
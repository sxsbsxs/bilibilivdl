package cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin

sealed class QrLoginUiEvent {
    data object JumpToPasswordLogin : QrLoginUiEvent()
}
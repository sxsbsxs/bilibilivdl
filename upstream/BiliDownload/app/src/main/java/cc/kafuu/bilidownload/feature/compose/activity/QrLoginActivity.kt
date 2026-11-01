package cc.kafuu.bilidownload.feature.compose.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cc.kafuu.bilidownload.common.core.compose.CoreCompActivity
import cc.kafuu.bilidownload.feature.compose.layout.QrLoginLayout
import cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin.QrLoginUiEvent
import cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin.QrLoginUiIntent
import cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin.QrLoginUiState
import cc.kafuu.bilidownload.feature.compose.viewmodel.qrlolgin.QrLoginViewModel
import cc.kafuu.bilidownload.feature.viewbinding.view.activity.LoginActivity

class QrLoginActivity : CoreCompActivity() {
    private val mViewModel by viewModels<QrLoginViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mViewModel.emit(QrLoginUiIntent.CreateQr)
    }

    @Composable
    override fun ViewContent() {
        val uiState by mViewModel.uiStateFlow.collectAsState()
        QrLoginLayout(uiState) { mViewModel.emit(it) }
        LaunchedEffect(uiState) { if (uiState is QrLoginUiState.Finished) finish() }
        LaunchedEffect(Unit) { mViewModel.collectEvent { onViewEvent(it) } }
    }

    private fun onViewEvent(event: QrLoginUiEvent) {
        when (event) {
            QrLoginUiEvent.JumpToPasswordLogin -> onJumpToPasswordLogin()
        }
    }

    private fun onJumpToPasswordLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
    }
}
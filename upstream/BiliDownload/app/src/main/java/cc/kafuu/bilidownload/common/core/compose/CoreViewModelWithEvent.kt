package cc.kafuu.bilidownload.common.core.compose

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 带 UiEvent 的 CoreViewModel
 */
abstract class CoreCompViewModelWithEvent<I, S, E>(initStatus: S) :
    CoreCompViewModel<I, S>(initStatus) {
    private val mUiEventFlow = MutableSharedFlow<ViewEventWrapper<E>>(extraBufferCapacity = 64)
    val uiEventFlow = mUiEventFlow.asSharedFlow()

    /**
     * 捕获View事件
     */
    suspend fun collectEvent(handle: suspend (E) -> Unit) {
        uiEventFlow.collect { event -> event.consumeIfNotHandled(handle) }
    }

    /**
     * 分发一个 UI Event（一次性事件）
     */
    protected fun E.send(): Boolean {
        return mUiEventFlow.tryEmit(ViewEventWrapper(this))
    }

    protected suspend fun E.awaitSend() = ViewEventWrapper(this).run {
        if (!mUiEventFlow.tryEmit(this)) {
            false
        } else {
            waitForConsumption()
            true
        }
    }
}

class ViewEventWrapper<out T>(private val content: T) {
    private val mMutex = Mutex()
    private val mHasHandled = MutableStateFlow(false)

    suspend fun consumeIfNotHandled(handle: suspend (T) -> Unit): Boolean = mMutex.withLock {
        if (mHasHandled.value) return@withLock false
        handle(content)
        mHasHandled.value = true
        return@withLock true
    }

    fun isHandled() = mHasHandled.value

    suspend fun waitForConsumption() {
        if (mHasHandled.value) return
        mHasHandled.first { it }
    }
}
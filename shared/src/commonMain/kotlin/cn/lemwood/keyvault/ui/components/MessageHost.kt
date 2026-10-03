package cn.lemwood.keyvault.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * 跨端轻量提示：Android 的 Toast 无法在桌面端复用，统一走 miuix Snackbar，
 * 由调用方把 [MessagePresenter.hostState] 挂到 Scaffold 的 snackbarHost 槽位。
 */
class MessagePresenter internal constructor(
    val hostState: SnackbarHostState,
    private val onShow: (String) -> Unit
) {
    fun show(message: String) = onShow(message)
}

@Composable
fun rememberMessagePresenter(): MessagePresenter {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) {
        MessagePresenter(hostState) { message ->
            scope.launch { hostState.showSnackbar(message) }
        }
    }
}

@Composable
fun MessageHost(presenter: MessagePresenter) {
    SnackbarHost(state = presenter.hostState)
}

/** 把来自 ViewModel 的一次性消息（如保存失败）投递到当前界面的 Snackbar。 */
@Composable
fun CollectMessages(messages: Flow<String>, presenter: MessagePresenter) {
    LaunchedEffect(messages, presenter) {
        messages.collect { presenter.show(it) }
    }
}

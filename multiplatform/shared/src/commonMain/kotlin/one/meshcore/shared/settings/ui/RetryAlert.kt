package one.meshcore.shared.settings.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.settings_alert_retry_connection_error
import one.meshcore.shared.resources.settings_alert_retry_ensure_connected
import one.meshcore.shared.resources.settings_alert_retry_retry
import one.meshcore.shared.resources.settings_alert_retry_unable_to_save
import org.jetbrains.compose.resources.stringResource

/** Retries before the alert switches to the terminal "unable to save" state. Mirrors iOS `maxRetries`. */
private const val MAX_RETRIES = 3

/**
 * State container for a settings retry alert, mirroring iOS `RetryAlertState`. A retryable save
 * failure calls [show]; after [MAX_RETRIES] the alert offers only a dismiss that runs
 * [onMaxRetriesExceeded]. A success or cancel calls [reset].
 */
class RetryAlertState {
    var isPresented by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    var retryCount by mutableIntStateOf(0)
        private set

    private var onRetry: (() -> Unit)? = null
    private var onMaxRetriesExceeded: (() -> Unit)? = null

    val isMaxRetriesExceeded: Boolean
        get() = retryCount >= MAX_RETRIES

    fun show(message: String, onRetry: () -> Unit, onMaxRetriesExceeded: () -> Unit) {
        retryCount += 1
        this.message = message
        this.onRetry = onRetry
        this.onMaxRetriesExceeded = onMaxRetriesExceeded
        isPresented = true
    }

    fun reset() {
        retryCount = 0
        onRetry = null
        onMaxRetriesExceeded = null
        isPresented = false
    }

    internal fun retry() {
        isPresented = false
        onRetry?.invoke()
    }

    internal fun acknowledgeMax() {
        val handler = onMaxRetriesExceeded
        reset()
        handler?.invoke()
    }

    internal fun cancel() = reset()
}

@Composable
fun rememberRetryAlertState(): RetryAlertState = remember { RetryAlertState() }

/**
 * Whether a save failure is transient and worth a retry: a command timeout, a lost/absent connection,
 * or a transport send failure. Mirrors iOS `SettingsServiceError.isRetryable`.
 */
fun Throwable.isRetryableSettingsError(): Boolean = when (this) {
    is MeshBridgeException ->
        code == BridgeErrorCode.TIMEOUT ||
            code == BridgeErrorCode.NOT_CONNECTED ||
            code == BridgeErrorCode.CONNECTION_LOST ||
            code == BridgeErrorCode.TRANSPORT_SEND_FAILED ||
            code == BridgeErrorCode.TRANSPORT_CONNECTION_FAILED
    // "No radio connected" and friends are thrown as IllegalStateException by the connection layer.
    is IllegalStateException -> true
    else -> false
}

/** The retry-counting alert. Renders nothing until [state] is presented. Mirrors iOS `RetryAlertModifier`. */
@Composable
fun RetryAlert(state: RetryAlertState) {
    if (!state.isPresented) return
    val maxed = state.isMaxRetriesExceeded
    AlertDialog(
        onDismissRequest = { if (maxed) state.acknowledgeMax() else state.cancel() },
        title = {
            Text(
                if (maxed) {
                    stringResource(Res.string.settings_alert_retry_unable_to_save)
                } else {
                    stringResource(Res.string.settings_alert_retry_connection_error)
                },
            )
        },
        text = {
            Text(
                if (maxed) stringResource(Res.string.settings_alert_retry_ensure_connected) else state.message,
            )
        },
        confirmButton = {
            if (maxed) {
                TextButton(onClick = { state.acknowledgeMax() }) {
                    Text(stringResource(Res.string.localizable_common_ok))
                }
            } else {
                TextButton(onClick = { state.retry() }) {
                    Text(stringResource(Res.string.settings_alert_retry_retry))
                }
            }
        },
        dismissButton = if (maxed) {
            null
        } else {
            { TextButton(onClick = { state.cancel() }) { Text(stringResource(Res.string.localizable_common_cancel)) } }
        },
    )
}

/** The retry alert, then the dialog for an error that will not be retried. */
@Composable
internal fun SettingsWriteFeedback(retryAlert: RetryAlertState, errorMessage: String?, onDismissError: () -> Unit) {
    RetryAlert(retryAlert)
    errorMessage?.let { message ->
        ThreadErrorDialog(message = message, onDismiss = onDismissError)
    }
}

package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineScope

internal data class OtherParamsSave(
    val onSave: suspend () -> Unit,
    val onSaving: (Boolean) -> Unit,
    override val onError: (String?) -> Unit,
    val onCommitted: () -> Unit,
    val onWriteInFlight: (Boolean) -> Unit,
    override val settingsErrorText: (Throwable) -> String,
    override val retryAlert: RetryAlertState,
    override val onMaxRetriesExceeded: () -> Unit,
) : SettingsWriteCallbacks

internal fun launchOtherParamsSave(scope: CoroutineScope, request: OtherParamsSave) {
    request.onSaving(true)
    request.onWriteInFlight(true)
    request.onError(null)
    scope.launchRetryableSettingsWrite(
        report = request.settingsWriteReport { launchOtherParamsSave(scope, request) },
        onFinished = {
            request.onSaving(false)
            request.onWriteInFlight(false)
        },
    ) {
        request.onSave()
        request.onCommitted()
    }
}

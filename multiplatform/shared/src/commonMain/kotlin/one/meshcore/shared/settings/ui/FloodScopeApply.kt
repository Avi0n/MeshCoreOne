package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineScope

internal data class FloodScopeApply(
    val name: String?,
    val inFlight: Boolean,
    val onApply: suspend (String?) -> Unit,
    val onInFlight: (Boolean) -> Unit,
    val onStatus: (String?) -> Unit,
    override val onError: (String?) -> Unit,
    override val settingsErrorText: (Throwable) -> String,
    override val retryAlert: RetryAlertState,
    override val onMaxRetriesExceeded: () -> Unit,
) : SettingsWriteCallbacks

internal fun launchFloodScopeApply(scope: CoroutineScope, request: FloodScopeApply) {
    if (request.inFlight) return
    request.onInFlight(true)
    request.onStatus(null)
    request.onError(null)
    scope.launchRetryableSettingsWrite(
        report = request.settingsWriteReport { launchFloodScopeApply(scope, request) },
        onFinished = { request.onInFlight(false) },
    ) {
        request.onApply(request.name)
    }
}

package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.settings.ManualRadioParams

internal data class ManualRadioApply(
    val params: ManualRadioParams,
    val onApply: suspend (ManualRadioParams) -> Unit,
    val onInFlight: (Boolean) -> Unit,
    override val onError: (String?) -> Unit,
    val onApplied: (Boolean) -> Unit,
    override val settingsErrorText: (Throwable) -> String,
    override val retryAlert: RetryAlertState,
    override val onMaxRetriesExceeded: () -> Unit,
) : SettingsWriteCallbacks

internal fun launchManualRadioApply(scope: CoroutineScope, request: ManualRadioApply) {
    scope.launchAppliedSettingsWrite(
        callbacks = request,
        onBusy = request.onInFlight,
        onApplied = request.onApplied,
        retry = { launchManualRadioApply(scope, request) },
    ) {
        request.onApply(request.params)
    }
}

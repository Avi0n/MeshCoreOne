package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.settings.NodesAutoAddDraft

internal const val BATTERY_CURVE_SAVE_FAILED_KEY = "save_failed"

internal data class DeviceRebootRequest(
    val onReboot: suspend () -> Unit,
    val onRebooting: (Boolean) -> Unit,
    val onError: (String?) -> Unit,
    val settingsErrorText: (Throwable) -> String,
)

@Suppress("TooGenericExceptionCaught")
internal fun launchDeviceReboot(scope: CoroutineScope, request: DeviceRebootRequest) {
    request.onRebooting(true)
    request.onError(null)
    scope.launch {
        try {
            request.onReboot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            request.onError(request.settingsErrorText(error))
        } finally {
            request.onRebooting(false)
        }
    }
}

internal data class BatteryCurveSave(
    val preset: OCVPreset,
    val values: List<Int>,
    val onSave: suspend (OCVPreset, List<Int>) -> Unit,
    val onSaving: (Boolean) -> Unit,
    val onErrorKey: (String?) -> Unit,
)

internal fun launchBatteryCurveSave(scope: CoroutineScope, request: BatteryCurveSave) {
    request.onSaving(true)
    request.onErrorKey(null)
    scope.launch {
        try {
            request.onSave(request.preset, request.values)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            request.onErrorKey(BATTERY_CURVE_SAVE_FAILED_KEY)
        } finally {
            request.onSaving(false)
        }
    }
}

internal data class NodesAutoAddApply(
    val draft: NodesAutoAddDraft,
    val onApply: suspend (NodesAutoAddDraft) -> Unit,
    val onApplying: (Boolean) -> Unit,
    override val onError: (String?) -> Unit,
    val onApplied: (Boolean) -> Unit,
    override val settingsErrorText: (Throwable) -> String,
    override val retryAlert: RetryAlertState,
    override val onMaxRetriesExceeded: () -> Unit,
) : SettingsWriteCallbacks

internal fun launchNodesAutoAdd(scope: CoroutineScope, request: NodesAutoAddApply) {
    scope.launchAppliedSettingsWrite(
        callbacks = request,
        onBusy = request.onApplying,
        onApplied = request.onApplied,
        retry = { launchNodesAutoAdd(scope, request) },
    ) {
        request.onApply(request.draft)
    }
}

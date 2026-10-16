package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPreset

internal data class RadioPresetWrite(
    val preset: RadioPreset,
    val repeatEnabled: Boolean,
    val inFlight: Boolean,
    val snapshot: RadioDeviceSnapshot,
    val onApplyPreset: suspend (RadioPreset) -> Unit,
    val onApplyRepeatPreset: suspend (RadioPreset) -> Unit,
    val otherParamsProvider: () -> DeviceOtherParamsSnapshot?,
    val onInFlight: (Boolean) -> Unit,
    override val onError: (String?) -> Unit,
    val onSnapshot: (RadioDeviceSnapshot) -> Unit,
    val onOtherParams: (DeviceOtherParamsSnapshot?) -> Unit,
    override val settingsErrorText: (Throwable) -> String,
    override val retryAlert: RetryAlertState,
    override val onMaxRetriesExceeded: () -> Unit,
) : SettingsWriteCallbacks

internal fun launchRadioPresetApply(scope: CoroutineScope, request: RadioPresetWrite) {
    if (request.inFlight) return
    request.onInFlight(true)
    request.onError(null)
    scope.launchRetryableSettingsWrite(
        report = request.settingsWriteReport { launchRadioPresetApply(scope, request) },
        onFinished = { request.onInFlight(false) },
    ) {
        if (request.repeatEnabled) {
            // Repeat mode retunes the frequency only; the device keeps its bandwidth/SF/CR.
            request.onApplyRepeatPreset(request.preset)
            request.onSnapshot(request.snapshot.copy(frequencyKHz = request.preset.frequencyKHz))
        } else {
            request.onApplyPreset(request.preset)
            request.onSnapshot(
                request.snapshot.copy(
                    frequencyKHz = request.preset.frequencyKHz,
                    bandwidthHz = request.preset.bandwidthHz,
                    spreadingFactor = request.preset.spreadingFactor,
                    codingRate = request.preset.codingRate,
                    appliedRadioPresetID = request.preset.id,
                ),
            )
            request.onOtherParams(request.otherParamsProvider())
        }
    }
}

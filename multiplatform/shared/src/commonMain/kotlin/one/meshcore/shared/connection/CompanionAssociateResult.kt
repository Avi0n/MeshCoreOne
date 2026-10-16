package one.meshcore.shared.connection

import kotlinx.coroutines.delay

/** Outcome of [CompanionDeviceRegistry.associate]. */
sealed class CompanionAssociateResult {
    data class Associated(val association: CompanionAssociation) : CompanionAssociateResult()
    data object Cancelled : CompanionAssociateResult()
    data object Unavailable : CompanionAssociateResult()
    data class Failed(val message: String? = null) : CompanionAssociateResult()
}

/**
 * CDM chooser missing or failed: fall through to the in-app NUS scan (macOS
 * `BluetoothScanPairingService` analog). A user cancel stays on the sheet.
 */
fun shouldFallBackToInAppBleScan(result: CompanionAssociateResult): Boolean =
    result is CompanionAssociateResult.Unavailable || result is CompanionAssociateResult.Failed

/** iOS `handlePickerUnavailableAfterForget` delay before a second `pairNewDevice`. */
const val FRESH_COMPANION_PAIRING_RETRY_DELAY_MS = 400L

/**
 * ASK `pickerRestricted` after leftover Forget: retry the system picker once.
 * Cancel stays a cancel; Associated is done.
 */
fun shouldRetryCompanionAssociateAfterLeftoverForget(result: CompanionAssociateResult): Boolean =
    shouldFallBackToInAppBleScan(result)

/**
 * iOS `DevicePairingError.cancelled`: the user declined Remove Accessory. Android's analog is
 * dismissing the Material forget dialog before Companion Device Manager `disassociate`.
 */
class DeviceForgetCancelledException : Exception("cancelled")

/**
 * After leftover Forget, iOS `completeFreshPairing` skips leftover re-check and retries
 * `pairNewDevice` once if ASK rejects the picker. CDM `Failed`/`Unavailable` get the same
 * one retry before in-app scan.
 */
suspend fun associateAfterLeftoverForget(
    associate: suspend () -> CompanionAssociateResult,
    delayMs: suspend (Long) -> Unit = { delay(it) },
): CompanionAssociateResult {
    val first = associate()
    if (!shouldRetryCompanionAssociateAfterLeftoverForget(first)) return first
    delayMs(FRESH_COMPANION_PAIRING_RETRY_DELAY_MS)
    return associate()
}

/**
 * iOS `scanForNewDevice` disconnects before ASK so a CDM or NUS scan cannot drop
 * a live GATT. Leftover Forget uses [completeFreshCompanionAssociate] instead.
 */
suspend fun disconnectLiveRadioBeforeCompanionPairing(isRadioLinked: Boolean, disconnect: suspend () -> Unit) {
    if (isRadioLinked) disconnect()
}

/**
 * iOS `startDeviceScan` keeps the live radio through leftover confirmation. `pairNewDevice`
 * stamps `.wantsConnection()` before ASK so a cancelled pair can restore the last radio.
 * Returns false when leftovers must be confirmed first.
 */
suspend fun prepareCompanionChooser(
    isRadioLinked: Boolean,
    hasLeftovers: Boolean,
    disconnect: suspend () -> Unit,
    noteWantsConnection: suspend () -> Unit,
): Boolean {
    if (hasLeftovers) return false
    disconnectLiveRadioBeforeCompanionPairing(isRadioLinked, disconnect)
    noteWantsConnection()
    return true
}

data class FreshCompanionAssociate(
    val isRadioLinked: Boolean,
    val disconnect: suspend () -> Unit,
    val isAvailable: Boolean,
    val associate: suspend () -> CompanionAssociateResult,
    val delayMs: suspend (Long) -> Unit = { delay(it) },
    val noteWantsConnection: suspend () -> Unit = {},
)

/**
 * Forget leftover then pair: disconnect a live session, skip leftover re-check, and retry
 * the system chooser once. [Unavailable] when Companion Device Manager is missing so the
 * caller can fall through to in-app scan.
 */
suspend fun completeFreshCompanionAssociate(pairing: FreshCompanionAssociate): CompanionAssociateResult {
    if (pairing.isRadioLinked) pairing.disconnect()
    pairing.noteWantsConnection()
    if (!pairing.isAvailable) return CompanionAssociateResult.Unavailable
    return associateAfterLeftoverForget(pairing.associate, pairing.delayMs)
}

package one.meshcore.shared.ui

import one.meshcore.shared.connection.DeviceConnectionState

/**
 * iOS `DeviceSelectionSheet` stays up when Change Device opens while Ready. Dismiss only after
 * this sheet started a connect and the radio left Ready then came back, and not while that
 * connect() is still in flight — Ready is published before the coroutine returns.
 */
internal fun shouldDismissConnectSheetAfterReady(
    startedConnect: Boolean,
    sawNonReady: Boolean,
    connectInFlight: Boolean = false,
): Boolean = startedConnect && sawNonReady && !connectInFlight

/**
 * Android scanning while a GATT link is up commonly drops the radio. Auto-start the connect-sheet
 * NUS scan only when there is no live or in-flight session; Scan Bluetooth still starts it on tap.
 */
internal fun shouldAutoStartConnectSheetBleScan(connectionState: DeviceConnectionState): Boolean =
    connectionState == DeviceConnectionState.DISCONNECTED

/**
 * [ConnectionManager.connect] returns after Ready, including the already-linked early return, so
 * success must dismiss even when the Ready collector does not re-fire.
 */
internal fun shouldDismissConnectSheetAfterAttempt(
    success: Boolean,
    otherApp: Boolean,
    dismissOnFailure: Boolean,
): Boolean = success || otherApp || dismissOnFailure

/** iOS `EmptyStateView`: no saved radios, no leftover associations, and Scan has not opened. */
internal fun shouldShowConnectRadioEmptyState(
    savedCount: Int,
    showScanSection: Boolean,
    needsSetupCount: Int = 0,
): Boolean = savedCount == 0 && needsSetupCount == 0 && !showScanSection

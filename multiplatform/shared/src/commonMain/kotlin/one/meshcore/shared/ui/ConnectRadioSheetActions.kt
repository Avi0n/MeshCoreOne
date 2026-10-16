package one.meshcore.shared.ui

import kotlinx.coroutines.launch
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.leftoverCompanionAssociationsForPairing
import one.meshcore.shared.connection.savedDeviceIsConnectedElsewhere

internal fun ConnectRadioSheetState.reloadSaved(call: ConnectSheetCall) {
    call.scope.launch { savedDevices = call.deps.actions.listSavedDevices() }
}

internal fun ConnectRadioSheetState.currentLeftovers(call: ConnectSheetCall): List<CompanionAssociation> =
    leftoverCompanionAssociationsForPairing(
        associations = call.deps.companionDevices.associations(),
        savedDevices = savedDevices,
        connectionState = call.connectionState(),
        lastConnectedId = lastConnectedId,
    )

internal fun ConnectRadioSheetState.refreshNeedsSetup(call: ConnectSheetCall) {
    needsSetup = currentLeftovers(call)
}

internal suspend fun ConnectRadioSheetState.openConnectSheet(call: ConnectSheetCall) {
    savedDevices = call.deps.actions.listSavedDevices()
    lastConnectedId = call.deps.actions.lastConnectedDeviceId()
    refreshNeedsSetup(call)
    if (shouldAutoStartConnectSheetBleScan(call.connectionState())) {
        startBleScanRequestingPermission(call.deps.permissions, call.deps.bleScan)
    }
}

internal fun ConnectRadioSheetState.onConnectionStateChanged(call: ConnectSheetCall) {
    val state = call.connectionState()
    if (state != DeviceConnectionState.READY) {
        sawNonReady = true
        return
    }
    if (shouldDismissConnectSheetAfterReady(
            startedConnect = startedConnect,
            sawNonReady = sawNonReady,
            connectInFlight = connecting,
        )
    ) {
        call.dismiss()
    }
}

internal fun ConnectRadioSheetState.refreshElsewhere(call: ConnectSheetCall) {
    refreshNeedsSetup(call)
    val handles = call.deps.actions.systemConnectedBleHandles()
    val state = call.connectionState()
    elsewhereIds = savedDevices
        .filter { savedDeviceIsConnectedElsewhere(it, state, handles) }
        .map { it.id }
        .toSet()
}

internal fun ConnectRadioSheetState.onBluetoothPermissionGranted(call: ConnectSheetCall, granted: Boolean) {
    val shouldScan = showScanSection || shouldAutoStartConnectSheetBleScan(call.connectionState())
    if (granted && shouldScan) {
        call.deps.bleScan.stopScan()
        call.deps.bleScan.startScan()
    }
}

internal fun ConnectRadioSheetState.beginConnect(
    call: ConnectSheetCall,
    dismissOnFailure: Boolean = false,
    block: suspend () -> Unit,
) {
    if (!prepareScannedDeviceConnect(connecting) { call.deps.bleScan.stopScan() }) return
    startedConnect = true
    call.scope.launch {
        connecting = true
        error = null
        val ok = runConnect(block, call.transportErrorText, call.settingsErrorText)
        if (!ok.success) connecting = false
        if (shouldDismissConnectSheetAfterAttempt(ok.success, ok.otherApp, dismissOnFailure)) {
            call.dismiss()
        } else {
            error = ok.message
        }
    }
}

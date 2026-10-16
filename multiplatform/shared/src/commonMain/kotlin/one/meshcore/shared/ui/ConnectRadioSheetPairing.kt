package one.meshcore.shared.ui

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.CompanionAssociateResult
import one.meshcore.shared.connection.FreshCompanionAssociate
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.connection.bleHandleIsHeldByOtherApp
import one.meshcore.shared.connection.completeFreshCompanionAssociate
import one.meshcore.shared.connection.prepareCompanionChooser
import one.meshcore.shared.connection.shouldFallBackToInAppBleScan
import one.meshcore.shared.connection.shouldRefuseSavedConnectForOtherApp
import one.meshcore.shared.connection.waitForOtherAppReconnection
import kotlin.coroutines.coroutineContext

internal fun ConnectRadioSheetState.showInAppScan(call: ConnectSheetCall) {
    showScanSection = true
    startBleScanRequestingPermission(call.deps.permissions, call.deps.bleScan)
}

internal suspend fun ConnectRadioSheetState.consumeCompanionAssociateResult(
    call: ConnectSheetCall,
    result: CompanionAssociateResult,
) {
    when (result) {
        is CompanionAssociateResult.Associated -> {
            val handle = result.association.handle
            val held = waitForOtherAppReconnection(
                isHeldNow = {
                    bleHandleIsHeldByOtherApp(
                        handle,
                        call.deps.connectionState.value,
                        call.deps.actions.systemConnectedBleHandles(),
                    )
                },
            )
            coroutineContext.ensureActive()
            if (held) {
                call.deps.actions.presentConnectedElsewhereAlert(handle)
                return
            }
            beginConnect(call, dismissOnFailure = true) { call.deps.actions.connect(handle) }
        }
        CompanionAssociateResult.Cancelled -> {
            if (shouldAutoStartConnectSheetBleScan(call.connectionState())) {
                startBleScanRequestingPermission(call.deps.permissions, call.deps.bleScan)
            }
        }
        else -> if (shouldFallBackToInAppBleScan(result)) showInAppScan(call)
    }
    refreshNeedsSetup(call)
}

internal fun ConnectRadioSheetState.beginCompanionPairing(call: ConnectSheetCall) {
    if (connecting) return
    call.deps.bleScan.stopScan()
    call.scope.launch {
        val leftovers = currentLeftovers(call)
        if (!prepareCompanionChooser(
                isRadioLinked = call.deps.connectionState.value.isRadioLinked,
                hasLeftovers = leftovers.isNotEmpty(),
                disconnect = { call.deps.actions.disconnectForPairing() },
                noteWantsConnection = { call.deps.actions.noteWantsConnection() },
            )
        ) {
            pendingSetup = leftovers
            refreshNeedsSetup(call)
            return@launch
        }
        // Only companion pairing latches; opening the saved-radio picker must not latch it.
        call.deps.actions.setPairingInProgress(true)
        if (!call.deps.companionDevices.isAvailable) {
            showInAppScan(call)
            return@launch
        }
        if (!call.deps.permissions.state.value.isGranted(OnboardingPermission.BLUETOOTH)) {
            call.deps.permissions.request(OnboardingPermission.BLUETOOTH)
        }
        consumeCompanionAssociateResult(call, call.deps.companionDevices.associate())
    }
}

internal fun ConnectRadioSheetState.consumePendingCompanionPairing(call: ConnectSheetCall) {
    if (call.deps.consumeCompanionPairingOnPicker()) beginCompanionPairing(call)
}

internal fun ConnectRadioSheetState.completeFreshCompanionPairing(call: ConnectSheetCall) {
    if (connecting) return
    call.deps.bleScan.stopScan()
    // Only companion pairing latches; opening the saved-radio picker must not latch it.
    call.deps.actions.setPairingInProgress(true)
    call.scope.launch {
        if (!call.deps.permissions.state.value.isGranted(OnboardingPermission.BLUETOOTH)) {
            call.deps.permissions.request(OnboardingPermission.BLUETOOTH)
        }
        consumeCompanionAssociateResult(
            call,
            completeFreshCompanionAssociate(
                FreshCompanionAssociate(
                    isRadioLinked = call.deps.connectionState.value.isRadioLinked,
                    disconnect = { call.deps.actions.disconnectForPairing() },
                    isAvailable = call.deps.companionDevices.isAvailable,
                    associate = { call.deps.companionDevices.associate() },
                    noteWantsConnection = { call.deps.actions.noteWantsConnection() },
                ),
            ),
        )
    }
}

internal fun ConnectRadioSheetState.confirmLeftoverForget(call: ConnectSheetCall) {
    val accessories = pendingSetup.orEmpty()
    pendingSetup = null
    accessories.forEach { call.deps.companionDevices.disassociate(it.handle) }
    refreshNeedsSetup(call)
    completeFreshCompanionPairing(call)
}

internal fun ConnectRadioSheetState.closeIfIdle(call: ConnectSheetCall) {
    if (!connecting) call.dismiss()
}

internal fun ConnectRadioSheetState.selectSaved(call: ConnectSheetCall, device: SavedRadioDevice) {
    val elsewhere = device.id in elsewhereIds
    val refuse = shouldRefuseSavedConnectForOtherApp(
        connectsViaWiFi = device.connectsViaWiFi,
        isHeldByOtherApp = elsewhere,
        isLastConnectedRadio = device.id == lastConnectedId,
    )
    if (refuse) {
        call.deps.actions.presentConnectedElsewhereAlert(device.id.value)
        call.dismiss()
    } else {
        beginConnect(call, dismissOnFailure = true) { call.deps.actions.connectSavedDevice(device) }
    }
}

internal fun ConnectRadioSheetState.dismissWifiEditorIfIdle() {
    if (!connecting) {
        showWiFi = false
        editingWifi = null
    }
}

/** Confirmed forget only; Cancel and outside dismissal leave the saved radio listed. */
internal fun ConnectRadioSheetState.forgetSavedDevice(call: ConnectSheetCall, device: SavedRadioDevice) {
    pendingForget = null
    call.scope.launch {
        call.deps.actions.deleteSavedDevice(device.id)
        reloadSaved(call)
    }
}

package one.meshcore.shared.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.CompanionAssociateResult
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.FreshCompanionAssociate
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.connection.completeFreshCompanionAssociate
import one.meshcore.shared.connection.leftoverCompanionAssociationsForPairing
import one.meshcore.shared.connection.prepareCompanionChooser
import one.meshcore.shared.connection.shouldFallBackToInAppBleScan
import one.meshcore.shared.connection.waitForOtherAppReconnection
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.ui.prepareScannedDeviceConnect

internal fun demoModeEnabledState(enabled: StateFlow<Boolean>?): StateFlow<Boolean> = enabled ?: MutableStateFlow(false)

internal data class OnboardingCompanionPairing(
    val associations: List<CompanionAssociation>,
    val savedDevices: List<SavedRadioDevice>,
    val connectionState: DeviceConnectionState,
    val lastConnectedId: Uuid?,
    val isRadioLinked: Boolean,
    val companionAvailable: Boolean,
    val bluetoothGranted: Boolean,
    val disconnect: suspend () -> Unit,
    val noteWantsConnection: suspend () -> Unit,
    val setPendingSetup: (List<CompanionAssociation>) -> Unit,
    val setPairingInProgress: (Boolean) -> Unit,
    val requestBluetooth: () -> Unit,
    val showInAppScan: () -> Unit,
    val associate: suspend () -> CompanionAssociateResult,
    val isHeld: suspend (String) -> Boolean,
    val onHeld: (String) -> Unit,
    val onConnect: (String) -> Unit,
    val onCancelled: () -> Unit,
)

internal fun launchPairConnect(
    scope: CoroutineScope,
    connect: suspend () -> Boolean,
    onSuccess: suspend () -> Unit,
    setConnecting: (Boolean) -> Unit,
) {
    scope.launch {
        setConnecting(true)
        val ok = connect()
        if (ok) {
            onSuccess()
        } else {
            setConnecting(false)
        }
    }
}

internal suspend fun consumeOnboardingCompanionResult(
    pairing: OnboardingCompanionPairing,
    result: CompanionAssociateResult,
) {
    when (result) {
        is CompanionAssociateResult.Associated -> {
            val handle = result.association.handle
            val held = waitForOtherAppReconnection(isHeldNow = { pairing.isHeld(handle) })
            currentCoroutineContext().ensureActive()
            if (held) {
                pairing.onHeld(handle)
                return
            }
            pairing.onConnect(handle)
        }
        CompanionAssociateResult.Cancelled -> pairing.onCancelled()
        else -> if (shouldFallBackToInAppBleScan(result)) pairing.showInAppScan()
    }
}

/**
 * The connecting guard and scan stop run before the coroutine, matching the click path that
 * ignores a second tap while a connect is already in flight.
 */
internal fun startOnboardingCompanionPairing(
    connecting: Boolean,
    stopScan: () -> Unit,
    scope: CoroutineScope,
    session: suspend () -> OnboardingCompanionPairing,
) {
    if (connecting) return
    stopScan()
    scope.launch { continueOnboardingCompanionPairing(session()) }
}

private suspend fun continueOnboardingCompanionPairing(pairing: OnboardingCompanionPairing) {
    val leftovers = leftoverCompanionAssociationsForPairing(
        associations = pairing.associations,
        savedDevices = pairing.savedDevices,
        connectionState = pairing.connectionState,
        lastConnectedId = pairing.lastConnectedId,
    )
    if (!prepareCompanionChooser(
            isRadioLinked = pairing.isRadioLinked,
            hasLeftovers = leftovers.isNotEmpty(),
            disconnect = pairing.disconnect,
            noteWantsConnection = pairing.noteWantsConnection,
        )
    ) {
        pairing.setPendingSetup(leftovers)
        return
    }
    pairing.setPairingInProgress(true)
    if (!pairing.companionAvailable) {
        pairing.showInAppScan()
        return
    }
    if (!pairing.bluetoothGranted) pairing.requestBluetooth()
    consumeOnboardingCompanionResult(pairing, pairing.associate())
}

internal fun completeFreshOnboardingPairing(
    connecting: Boolean,
    setPairingInProgress: (Boolean) -> Unit,
    stopScan: () -> Unit,
    scope: CoroutineScope,
    session: suspend () -> OnboardingCompanionPairing,
) {
    if (connecting) return
    setPairingInProgress(true)
    stopScan()
    scope.launch {
        val pairing = session()
        if (!pairing.bluetoothGranted) pairing.requestBluetooth()
        consumeOnboardingCompanionResult(
            pairing,
            completeFreshCompanionAssociate(
                FreshCompanionAssociate(
                    isRadioLinked = pairing.isRadioLinked,
                    disconnect = pairing.disconnect,
                    isAvailable = pairing.companionAvailable,
                    associate = pairing.associate,
                    noteWantsConnection = pairing.noteWantsConnection,
                ),
            ),
        )
    }
}

internal fun forgetLeftoverCompanionAccessories(
    accessories: List<CompanionAssociation>,
    disassociate: (String) -> Unit,
) {
    accessories.forEach { disassociate(it.handle) }
}

internal fun startScannedDeviceConnect(connecting: Boolean, stopScan: () -> Unit, connect: () -> Unit) {
    if (prepareScannedDeviceConnect(connecting, stopScan)) connect()
}

internal fun openOnboardingConnectPicker(
    show: Boolean,
    consumePicker: () -> Unit,
    consumeCompanion: () -> Unit,
    begin: () -> Unit,
) {
    if (!show) return
    consumePicker()
    consumeCompanion()
    begin()
}

package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import one.meshcore.shared.simulator.MockDataProvider
import one.meshcore.shared.transport.BondLostException
import one.meshcore.shared.transport.EncryptionTimeoutException

/**
 * Stands down last-radio reconnect during companion pairing / scan-new (iOS
 * `isPairingFlowActive`). Opening the picker to tap a saved radio does not set this.
 */
fun ConnectionManager.isPairingInProgress(): Boolean = pairingInProgress

/** Live wanted intent wins over a persisted disconnect from an earlier connection request. */
suspend fun ConnectionManager.isExplicitlyDisconnected(): Boolean {
    while (!wantsConnection) {
        val generation = connectGeneration
        val intent = intentStore?.restore()
        if (ownsConnectAttempt(generation)) return intent?.isUserDisconnected == true && !wantsConnection
    }
    return false
}

fun ConnectionManager.setPairingInProgress(inProgress: Boolean) {
    pairingInProgress = inProgress
    val previousTransition = pairingTransitionJob
    if (inProgress) {
        val abandonLiveLink = mutableState.value is ConnectionState.Reconnecting ||
            mutableState.value is ConnectionState.Connecting ||
            reconnectJob?.isActive == true
        cancelReconnect()
        if (abandonLiveLink) {
            mutableState.value = ConnectionState.Disconnected
            pairingTransitionJob = scope.launch {
                previousTransition?.join()
                tearDownCurrent(stopSession = false)
            }
        }
    } else {
        pairingTransitionJob = scope.launch {
            previousTransition?.join()
            resumeConnectionAfterPairing()
        }
    }
}

/** iOS `pairNewDevice` stamps `.wantsConnection()` before ASK so a cancelled pair can restore. */
suspend fun ConnectionManager.noteWantsConnection() {
    wantsConnection = true
    persistConnectionIntent(ConnectionIntent.WantsConnection())
}

suspend fun ConnectionManager.disconnectForPairing() {
    setPairingInProgress(true)
    val previousTransition = pairingTransitionJob
    val generation = connectGeneration
    val target = currentDeviceId
    // Closing the chooser cancels its await while the manager finishes the owned cleanup.
    val transition = scope.async(start = CoroutineStart.LAZY) {
        previousTransition?.join()
        if (!ownsConnectAttempt(generation) || currentDeviceId != target || !wantsConnection) {
            throw CancellationException(PAIRING_PREPARATION_SUPERSEDED)
        }
        disconnect(DisconnectReason.PAIRING_NEW_DEVICE)
        if (currentDeviceId != target || !wantsConnection || mutableState.value != ConnectionState.Disconnected) {
            throw CancellationException(PAIRING_PREPARATION_SUPERSEDED)
        }
    }
    pairingTransitionJob = transition
    transition.start()
    transition.await()
}

private suspend fun ConnectionManager.resumeConnectionAfterPairing() {
    if (pairingInProgress || !wantsConnection) return
    if (!canArmBleReconnect(mutableState.value) || reconnectJob?.isActive == true) return
    val target = currentDeviceId ?: return
    when {
        target == MockDataProvider.SIMULATOR_HANDLE -> resumeSimulatorAfterPairing()
        currentTransportType == TransportType.WIFI -> {
            val host = wifiHost ?: return
            val port = wifiPort ?: return
            startWiFiReconnect(host, port, target)
        }
        else -> armBleReconnectIfWanted()
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ConnectionManager.resumeSimulatorAfterPairing() {
    try {
        simulatorConnect()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger.warning("$PAIRING_RECONNECT_FAILED: ${error.message ?: error::class.simpleName}")
    }
}

private const val PAIRING_RECONNECT_FAILED = "Pairing reconnect failed"
private const val PAIRING_PREPARATION_SUPERSEDED = "Pairing preparation was superseded"

/**
 * Arms the BLE auto-reconnect loop for the wanted radio when nothing else is driving a connect, and
 * reports whether it armed. Mirrors the Swift `startReconnectionWatchdog()` re-arms that sit outside
 * the drop path: `appDidBecomeActive` re-arms while disconnected, and a launch auto-reconnect whose
 * `connect` threw arms before returning. A drop arms the loop on its own, but a connect that never
 * established one leaves the radio wanted with nothing retrying, and Android has no OS-level pending
 * connect to fall back on.
 *
 * Refuses while a WiFi connection owns the transport (that schedule is chosen in [handleLinkDrop]),
 * while any state other than [ConnectionState.Disconnected] or [ConnectionState.BondLost] is live,
 * and while a loop is already running, so it can be called unconditionally on every foreground.
 * [ConnectionState.BondLost] is armable because iOS parks the same episode at `.disconnected` and
 * re-arms from `appDidBecomeActive`; a user who re-paired in system settings must get another try.
 */
fun ConnectionManager.armBleReconnectIfWanted(): Boolean {
    if (!bluetoothConnectAuthorized()) return false
    if (pairingInProgress) {
        logger.info(BLE_OPPORTUNISTIC_SKIP_PAIRING)
        return false
    }
    val deviceId = currentDeviceId ?: return false
    val armable = wantsConnection &&
        currentTransportType != TransportType.WIFI &&
        canArmBleReconnect(mutableState.value) &&
        reconnectJob?.isActive != true
    if (armable) startBleReconnect(deviceId)
    return armable
}

/** True when the BLE auto-reconnect loop is running. Used by the foreground health dump. */
fun ConnectionManager.isBleReconnectInProgress(): Boolean =
    currentTransportType != TransportType.WIFI && reconnectJob?.isActive == true

// Maps a connect-throwable to the policy's failure vocabulary, keeping exception type-inspection out
// of the reconnect loop. A definitive bond failure parks BondLost for guided re-pair while the
// watchdog keeps ticking; an encryption timeout is transient but tallied toward that escalation;
// everything else is a plain transient failure.
internal fun ConnectionManager.connectFailureKind(error: Throwable): ConnectFailureKind = when (error) {
    is BondLostException -> ConnectFailureKind.DEFINITIVE_BOND
    is EncryptionTimeoutException -> ConnectFailureKind.ENCRYPTION_TIMEOUT
    else -> ConnectFailureKind.TRANSIENT
}

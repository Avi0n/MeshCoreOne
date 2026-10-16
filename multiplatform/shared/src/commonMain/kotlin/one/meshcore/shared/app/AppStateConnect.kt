package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.connection.PairingFailure
import one.meshcore.shared.connection.SavedDeviceConnectError
import one.meshcore.shared.connection.SavedRadioDevice
import one.meshcore.shared.connection.classifySavedDeviceConnectError
import one.meshcore.shared.connection.fetchSavedConnectableDevices
import one.meshcore.shared.connection.isBluetoothUnauthorized
import one.meshcore.shared.connection.simulatorConnect
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.simulator.MockDataProvider
import one.meshcore.shared.transport.BondLostException
import one.meshcore.shared.transport.EncryptionTimeoutException
import one.meshcore.shared.transport.MeshLinkConnectException

/**
 * Explicit reconnect of the last radio (Quick Settings tile). WiFi first from the saved
 * methods, then the BLE method handle. Never BLE-connects a WiFi `host:port` last handle.
 */
suspend fun AppStateViewModel.reconnectLastDevice(): Boolean {
    val lastId = lastConnectionStore.deviceId() ?: return false
    if (MockDataProvider.isSimulatorDeviceId(lastId)) {
        connectSimulator()
        return true
    }
    val saved = connectionManager.fetchSavedConnectableDevices().firstOrNull { it.id == lastId }
        ?: return false
    return reconnectSavedRadio(saved)
}

/** Connects the bundled simulator/demo radio and seeds the store. */
suspend fun AppStateViewModel.connectSimulator() {
    connectionUi.hideDisconnectedPill()
    runConnect({ connectionUi.presentConnectionFailure(null) }) {
        connectionManager.simulatorConnect()
    }
}

/**
 * Routes a BLE connect failure. A radio the app holds no reachable row for was just chosen in the
 * picker, so its failure is a fresh pairing failure — the iOS split between `pairNewDevice` and a
 * connect to an already-paired radio — and a bond refused there is a rejected PIN rather than the dead
 * saved pairing the same throw means on a known radio. The row is read only once the attempt has
 * failed, which keeps a Room read off the connect path the responder is waiting on.
 */
internal suspend fun AppStateViewModel.presentConnectFailure(deviceId: String, error: Throwable) {
    if (isBluetoothUnauthorized(error)) {
        connectionUi.presentSavedDeviceConnectFailure(deviceId, savedDeviceError(error))
        return
    }
    if (isSavedRadio(deviceId)) {
        connectionUi.presentSavedDeviceConnectFailure(deviceId, savedDeviceError(error))
        return
    }
    val pairingFailure = freshPairingFailure(deviceId, error)
    if (pairingFailure != null) {
        connectionUi.presentFreshPairingFailure(pairingFailure)
    } else {
        connectionUi.presentConnectionFailure(null)
    }
}

// Whether the app already holds a reachable row for this radio, matched on the transport handle the same
// way the alert's bond-removal recovery resolves one, and on the row id for a caller that passes it.
private suspend fun AppStateViewModel.isSavedRadio(deviceId: String): Boolean = connectionManager
    .fetchSavedConnectableDevices()
    .any { it.bleHandle == deviceId || it.id == Uuid.normalized(deviceId) }

/**
 * Runs a user-initiated connect and routes its failure through [present] before rethrowing, so the
 * caller's own spinner still unwinds. Without this the alert flow has no production writer at all and
 * a failed connect is silent.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun AppStateViewModel.runConnect(present: suspend (Throwable) -> Unit, connect: suspend () -> Unit) {
    try {
        connect()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        present(error)
        throw error
    }
}

internal suspend fun AppStateViewModel.refreshDisconnectedPill(suppress: Boolean) {
    connectionUi.updateDisconnectedPillState(
        connectionState = connectionState.value,
        lastConnectedDeviceId = lastConnectionStore.deviceId()?.value,
        shouldSuppressDisconnectedPill = suppress,
    )
}

internal suspend fun AppStateViewModel.reconnectSavedRadio(saved: SavedRadioDevice): Boolean {
    val wifi = saved.wifi
    if (wifi != null) {
        connectViaWiFi(wifi.host, wifi.port)
        return true
    }
    return connectSavedBle(saved.bleHandle)
}

private suspend fun AppStateViewModel.connectSavedBle(handle: String?): Boolean {
    val ble = handle ?: return false
    connect(ble)
    return true
}

internal fun savedDeviceError(error: Throwable): SavedDeviceConnectError = classifySavedDeviceConnectError(error)

/**
 * Classifies a failure from pairing a radio the app has never held, the way iOS `presentFreshPairingFailure`
 * does. A refused bond on a first pair is a rejected PIN, not the dead saved pairing the same throw means on
 * a known radio. A GATT encryption timeout (status 8/22/133) is the Android shape of that same refused PIN,
 * and a link that will not come up is still a pairing failure and keeps its retry. Anything else is not
 * about pairing at all, and `null` routes it to the plain failure alert.
 */
internal fun freshPairingFailure(deviceId: String, error: Throwable): PairingFailure? = when (error) {
    is BondLostException, is EncryptionTimeoutException ->
        PairingFailure.ConnectionFailed(deviceId, isAuthenticationFailure = true)
    is MeshLinkConnectException -> PairingFailure.ConnectionFailed(deviceId, isAuthenticationFailure = false)
    else -> null
}

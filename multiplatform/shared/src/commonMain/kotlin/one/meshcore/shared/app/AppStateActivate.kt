package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.connection.ConnectionIntent
import one.meshcore.shared.connection.DeviceSelectionFilter
import one.meshcore.shared.connection.activateLastBle
import one.meshcore.shared.connection.armBleReconnectIfWanted
import one.meshcore.shared.connection.connectViaWiFi
import one.meshcore.shared.connection.isPairingInProgress
import one.meshcore.shared.connection.persistActivateReconnectFailure
import one.meshcore.shared.connection.simulatorConnect
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback
import one.meshcore.shared.simulator.MockDataProvider

/**
 * The launch auto-reconnect decision, intent-gated and silent (it drives the Syncing pill rather
 * than any modal): skip entirely if the user last disconnected on purpose; otherwise reconnect to
 * the last device, WiFi-first (its persisted host/port) and falling through to BLE.
 */
suspend fun AppStateViewModel.activate(): AutoReconnectDecision = skippedLaunch() ?: activateLastDevice()

private suspend fun AppStateViewModel.skippedLaunch(): AutoReconnectDecision? {
    if (userDisconnected()) return AutoReconnectDecision.SKIP_USER_DISCONNECTED
    if (connectionManager.isPairingInProgress()) return AutoReconnectDecision.SKIP_PAIRING_IN_PROGRESS
    return null
}

private suspend fun AppStateViewModel.userDisconnected(): Boolean {
    val intent = intentStore.restore()
    if (!intent.isUserDisconnected) return false
    refreshDisconnectedPill(suppress = true)
    return true
}

private suspend fun AppStateViewModel.activateLastDevice(): AutoReconnectDecision {
    val lastId = lastConnectionStore.deviceId() ?: return AutoReconnectDecision.NOTHING_TO_RECONNECT
    intentStore.persist(ConnectionIntent.WantsConnection())
    if (MockDataProvider.isSimulatorDeviceId(lastId)) return activateSimulator()
    return activateSavedRadio(lastId)
}

private suspend fun AppStateViewModel.activateSimulator(): AutoReconnectDecision {
    val connected = launchConnectionAccepted { connectionManager.simulatorConnect() }
    return if (connected) {
        AutoReconnectDecision.RECONNECT_SIMULATOR
    } else {
        AutoReconnectDecision.NOTHING_TO_RECONNECT
    }
}

private suspend fun AppStateViewModel.activateSavedRadio(lastId: Uuid): AutoReconnectDecision {
    val methods = database.deviceDao().getById(lastId)?.connectionMethods ?: emptyList()
    val wifi = methods.filterIsInstance<ConnectionMethod.WiFi>().firstOrNull()
    if (wifi != null && connectedOverWifi(wifi)) return AutoReconnectDecision.RECONNECT_WIFI
    return activateBleOrWifiFallback(methods, wifi)
}

private suspend fun AppStateViewModel.connectedOverWifi(wifi: ConnectionMethod.WiFi): Boolean =
    launchConnectionAccepted { connectionManager.connectViaWiFi(wifi.host, wifi.port) }

internal suspend fun launchConnectionAccepted(connect: suspend () -> Unit): Boolean = runExceptionFallback({ false }) {
    connect()
    true
}

private suspend fun AppStateViewModel.activateBleOrWifiFallback(
    methods: List<ConnectionMethod>,
    wifi: ConnectionMethod.WiFi?,
): AutoReconnectDecision {
    val lastHandle = lastConnectionStore.handle()
    val bleHandle = DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = lastHandle)
    if (bleHandle != null) {
        armLaunchBle(bleHandle)
        return AutoReconnectDecision.RECONNECT_BLE
    }
    return if (wifi != null) AutoReconnectDecision.RECONNECT_WIFI else AutoReconnectDecision.NOTHING_TO_RECONNECT
}

// A launch reconnect that throws never reaches the drop path, so nothing arms the retry loop.
// Adopt, other-app skip, and connect(to:) live in activateLastBle.
@Suppress("TooGenericExceptionCaught")
private suspend fun AppStateViewModel.armLaunchBle(bleHandle: String) {
    try {
        connectionManager.activateLastBle(bleHandle)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        connectionManager.persistActivateReconnectFailure(bleHandle, error)
        connectionManager.armBleReconnectIfWanted()
    }
}

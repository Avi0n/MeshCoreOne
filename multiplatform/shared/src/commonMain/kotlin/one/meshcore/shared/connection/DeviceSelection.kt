package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid

/**
 * A saved radio the user can reconnect to from the connect sheet (iOS `DeviceSelectionSheet`).
 *
 * [wifi] is preferred when present. [bleHandle] is the Kable peripheral id (MAC on Android), not the
 * stable Device row [id] — row ids are a non-reversible hash of the handle (see [StableIds]).
 */
data class SavedRadioDevice(
    val id: Uuid,
    val name: String,
    val wifi: ConnectionMethod.WiFi?,
    val bleHandle: String?,
    val lastConnected: Double,
) {
    /** Whether a tap should route over WiFi (preferred when both transports exist). */
    val connectsViaWiFi: Boolean get() = wifi != null

    val isConnectable: Boolean get() = wifi != null || !bleHandle.isNullOrBlank()
}

/**
 * iOS `connect(to:)` joins a same-device Connecting attempt or auto-reconnect even when
 * the caller passes `forceReconnect`. It only abandons when the UI is already Disconnected
 * (stuck OS pending connect) and a session rebuild does not own that handle.
 */
data class InFlightBleJoin(
    val forceReconnect: Boolean,
    val connectingSameDevice: Boolean,
    val reconnectInProgressForSameDevice: Boolean,
    val connectionIsDisconnected: Boolean = false,
    val sessionRebuildOwnsHandle: Boolean = false,
    val connectionIsBondLost: Boolean = false,
)

fun shouldJoinInFlightBleConnect(join: InFlightBleJoin): Boolean {
    if (join.connectingSameDevice) return true
    if (!join.reconnectInProgressForSameDevice) return false
    // iOS parks bond-loss at `.disconnected` with the coordinator cycle cleared, so a later
    // tap falls through to an immediate `connect(to:)` instead of joining the sleeper.
    if (join.connectionIsBondLost) return false
    val abandonStuck = join.forceReconnect && join.connectionIsDisconnected && !join.sessionRebuildOwnsHandle
    return !abandonStuck
}

/**
 * iOS `startAdoptingSystemConnectedPeripheral`: when the OS already has a GATT to this handle,
 * a reconnect must not wait on advertisement (`connectGatt(autoConnect=true)` never completes
 * because a connected radio typically stops advertising).
 */
fun shouldAdoptSystemConnectedGatt(handle: String?, systemConnectedHandles: Set<String>): Boolean {
    val key = handle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return systemConnectedHandles.any { it.equals(key, ignoreCase = true) }
}

/**
 * iOS `startAdoptingLastSystemConnectedPeripheralIfAvailable`: the last radio is in the OS GATT
 * set, ConnectionManager is idle, and this process does not already own the link. Activate and
 * BLE health-check start a direct connect instead of treating that as another app.
 */
data class IdleSystemGatt(
    val lastHandle: String?,
    val targetHandle: String?,
    val connectionState: ConnectionState,
    val reconnectInProgress: Boolean,
    val linkAlreadyOurs: Boolean,
    val transportIsWifi: Boolean,
    val systemConnectedHandles: Set<String>,
)

fun shouldAdoptLastSystemConnectedWhileIdle(idle: IdleSystemGatt): Boolean {
    if (idle.transportIsWifi) return false
    if (idle.reconnectInProgress) return false
    if (idle.linkAlreadyOurs) return false
    if (!canArmBleReconnect(idle.connectionState)) return false
    if (!shouldAdoptSystemConnectedGatt(idle.targetHandle, idle.systemConnectedHandles)) return false
    val last = idle.lastHandle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val target = idle.targetHandle?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return last.equals(target, ignoreCase = true)
}

/**
 * iOS `reconcileConnectedBLEAppStack` rebuild branch: the transport is up but session or
 * services are missing. An in-flight connect or auto-reconnect already owns handshake, so
 * health-check must not start a second one.
 */
fun shouldRebuildConnectedBleAppStack(
    linkConnected: Boolean,
    hasSession: Boolean,
    hasServices: Boolean,
    reconnectInProgress: Boolean,
    connectCeremonyInFlight: Boolean,
): Boolean {
    if (!linkConnected) return false
    if (reconnectInProgress) return false
    if (connectCeremonyInFlight) return false
    return !hasSession || !hasServices
}

/**
 * Live scan RSSI for a saved BLE handle. Kable's advertisement id and the stored reconnect
 * handle can differ only in letter case (Android MAC vs identifier string), so a case-sensitive
 * map lookup would leave an in-range radio dimmed as out of range.
 */
fun bleScanRssiForHandle(handle: String?, signalByHandle: Map<String, Int>): Int? {
    val key = handle?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    signalByHandle[key]?.let { return it }
    return signalByHandle.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
}

/** Firmware default TCP port; iOS `ConnectionMethod.shortDescription` omits it. */
const val DEFAULT_WIFI_TCP_PORT = 5000

/** Port field seed matching iOS `WiFiEditSheet` (`"5000"` until a saved port exists). */
fun wifiPortDraftText(savedPort: Int?): String = savedPort?.toString() ?: DEFAULT_WIFI_TCP_PORT.toString()

/**
 * Saved-list subtitle for a WiFi method, matching iOS `ConnectionMethod.shortDescription`:
 * host alone on the default port, otherwise `host:port`.
 */
fun wifiShortDescription(wifi: ConnectionMethod.WiFi): String =
    if (wifi.port == DEFAULT_WIFI_TCP_PORT) wifi.host else "${wifi.host}:${wifi.port}"

/**
 * Filters saved `Device` rows to those the user can actually reach — mirrors iOS
 * `DeviceSelectionFilter` for the Android/macOS path (no AccessorySetupKit registry).
 *
 * Backup shadows have Bluetooth methods stripped and no live handle; they stay hidden until the user
 * pairs again and reconcile re-attaches reachability.
 */
object DeviceSelectionFilter {
    /**
     * Resolves the BLE reconnect handle for a row: prefer the transport id stored on the Bluetooth
     * method's `displayName` (Android persists the Kable id there), else the last-connection handle
     * when this row is the last-connected device (legacy rows written before handle-on-method).
     * A last handle that is a WiFi `host:port` is not a BLE handle.
     */
    fun bleHandle(methods: List<ConnectionMethod>, lastHandleIfThisDevice: String?): String? {
        val methodHandle = methods.filterIsInstance<ConnectionMethod.Bluetooth>()
            .mapNotNull { method -> method.displayName?.takeIf { it.isNotBlank() } }
            .firstOrNull()
        if (methodHandle != null) return methodHandle
        val last = lastHandleIfThisDevice?.takeIf { it.isNotBlank() }
        // finishConnection persists the live transport handle. After a WiFi session that is
        // `host:port`, which must not be fed to BLE connect.
        return last?.takeUnless { isWifiTransportHandle(it, methods) }
    }

    /** `host:port` written by [ConnectionManager] for a WiFi session. */
    fun wifiTransportHandle(host: String, port: Int): String = "$host:$port"

    private fun isWifiTransportHandle(handle: String, methods: List<ConnectionMethod>): Boolean =
        methods.filterIsInstance<ConnectionMethod.WiFi>().any { wifiTransportHandle(it.host, it.port) == handle }

    fun isConnectable(methods: List<ConnectionMethod>, bleHandle: String?): Boolean {
        if (methods.any { it is ConnectionMethod.WiFi }) return true
        return !bleHandle.isNullOrBlank()
    }

    fun primaryWifi(methods: List<ConnectionMethod>): ConnectionMethod.WiFi? =
        methods.filterIsInstance<ConnectionMethod.WiFi>().firstOrNull()
}

package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.transport.deviceHandlePrefix

/**
 * The most recent disconnect summary, persisted across launches. The primary artifact a responder
 * sends back after a radio drops, so it is written at every way out of a connection. Mirrors iOS
 * `ConnectionManager.lastDisconnectDiagnostic`.
 */
suspend fun ConnectionManager.lastDisconnectDiagnostic(): String? = lastConnectionStore?.disconnectDiagnostic()

/**
 * Best-effort BLE/link snapshot for a diagnostics export. Kotlin has no CoreBluetooth
 * state-machine dump, so this reports the live transport, connection rung, session, and
 * reconnect flags (iOS `currentBLEDiagnosticsSummary`).
 */
fun ConnectionManager.bleDiagnosticsSummary(): String {
    val live = mutableState.value
    val handle = live.deviceId?.let { deviceHandlePrefix(it) } ?: "none"
    return "BLE: transport=${currentTransportType?.name?.lowercase() ?: "none"}, " +
        "state=${live.toDeviceConnectionState().name}, " +
        "sessionPresent=${session != null}, " +
        "servicesPresent=${services != null}, " +
        "isAutoReconnecting=${live is ConnectionState.Reconnecting}, " +
        "connectedDevice=$handle"
}

/**
 * A launch auto-reconnect that threw never reaches [handleLinkDrop], so the diagnostic must
 * be written here. Mirrors iOS `source=activate.autoReconnectFailed`.
 */
suspend fun ConnectionManager.persistActivateReconnectFailure(deviceId: String, error: Throwable) {
    persistDisconnectDiagnostic(
        "source=activate.autoReconnectFailed, " +
            "device=$deviceId, " +
            "error=${error.message ?: error::class.simpleName}, " +
            "intent=${connectionIntentSummary()}",
    )
}

/**
 * iOS activate BLE path after WiFi fallthrough: adopt a system-connected last radio while idle,
 * silently skip a fight with another app, else [connect]. An in-flight watchdog is joined,
 * not torn down.
 */
suspend fun ConnectionManager.activateLastBle(handle: String) {
    requireBleConnectAuthorization(handle)
    if (startAdoptingLastSystemConnectedIfAvailable("activate", handle)) return
    if (bleHandleIsHeldByOtherApp(
            handle,
            mutableState.value.toDeviceConnectionState(),
            systemConnectedHandles(),
        )
    ) {
        wantsConnection = true
        currentDeviceId = handle
        currentTransportType = TransportType.BLUETOOTH
        persistConnectionIntent(ConnectionIntent.WantsConnection())
        persistDisconnectDiagnostic(
            "source=activate.autoReconnectSkippedOtherApp, " +
                "device=$handle, " +
                "intent=${connectionIntentSummary()}",
        )
        logger.info(BLE_FOREGROUND_SKIP_OTHER_APP)
        armBleReconnectIfWanted()
        return
    }
    connect(handle)
}

/**
 * iOS `startAdoptingLastSystemConnectedPeripheralIfAvailable`. When the last radio is still
 * system-connected and this manager is idle, start a direct GATT connect instead of waiting
 * for advertisement or treating the handle as another app. Returns `true` if an adoption
 * attempt started (success or failure); `false` if the idle/system-connected guards failed.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun ConnectionManager.startAdoptingLastSystemConnectedIfAvailable(
    context: String,
    deviceId: String? = null,
): Boolean {
    if (!bluetoothConnectAuthorized()) return false
    val handle = deviceId ?: currentDeviceId ?: lastConnectionStore?.handle() ?: return false
    val lastHandle = lastConnectionStore?.handle() ?: currentDeviceId
    if (!shouldAdoptLastSystemConnectedWhileIdle(
            IdleSystemGatt(
                lastHandle = lastHandle,
                targetHandle = handle,
                connectionState = mutableState.value,
                reconnectInProgress = isBleReconnectInProgress(),
                linkAlreadyOurs = link?.isConnected == true,
                transportIsWifi = currentTransportType == TransportType.WIFI,
                systemConnectedHandles = systemConnectedHandles(),
            ),
        )
    ) {
        return false
    }
    logger.warning(bleAdoptSystemConnectedLine(context, deviceHandlePrefix(handle)))
    persistDisconnectDiagnostic(
        "source=$context.adoptSystemConnectedPeripheral, " +
            "device=$handle, " +
            "intent=${connectionIntentSummary()}",
    )
    try {
        connect(handle)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        persistActivateReconnectFailure(handle, error)
        armBleReconnectIfWanted()
    }
    return true
}

/**
 * The high-level connection intent as one diagnostic token, mirroring iOS
 * `ConnectionManager.connectionIntentSummary`. Diagnostic text, never UI copy. Reads the persisted
 * user-disconnected bit rather than a second in-memory copy of it, so it cannot drift from the bit
 * auto-reconnect actually gates on.
 */
suspend fun ConnectionManager.connectionIntentSummary(): String = when {
    wantsConnection -> "wantsConnection"
    intentStore?.restore() == ConnectionIntent.UserDisconnected -> "userDisconnected"
    else -> "none"
}

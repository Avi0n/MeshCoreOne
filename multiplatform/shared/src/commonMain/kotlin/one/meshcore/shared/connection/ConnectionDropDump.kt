package one.meshcore.shared.connection

import one.meshcore.shared.transport.deviceHandlePrefix

/** iOS `disconnect(reason:)` dump: reason uses the Swift `DisconnectReason` raw value. */
internal fun DisconnectReason.dumpLabel(): String = when (this) {
    DisconnectReason.USER_INITIATED -> "user initiated disconnect"
    DisconnectReason.PAIRING_NEW_DEVICE -> "switching to new device"
    DisconnectReason.RESYNC_FAILED -> "resync failed after 3 attempts"
    DisconnectReason.WIFI_ADDRESS_CHANGE -> "WiFi address changed"
}

internal fun TransportType?.dumpName(): String = when (this) {
    TransportType.BLUETOOTH -> "bluetooth"
    TransportType.WIFI -> "wifi"
    null -> "none"
}

internal fun connectionStateDumpName(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> "disconnected"
    is ConnectionState.Connecting -> "connecting"
    is ConnectionState.Connected -> "connected"
    is ConnectionState.Syncing -> "syncing"
    is ConnectionState.Ready -> "ready"
    is ConnectionState.Reconnecting -> "autoReconnecting"
    is ConnectionState.BondLost -> "bondLost"
}

internal fun disconnectingFromDeviceLine(
    reason: String,
    transport: String,
    device: String,
    initialState: String,
    intent: String,
): String = "Disconnecting from device (" +
    "reason: $reason, " +
    "transport: $transport, " +
    "device: $device, " +
    "initialState: $initialState, " +
    "intent: $intent" +
    ")"

internal fun disconnectedLine(drop: ConnectionDropContext, finalState: String): String = "Disconnected (" +
    "reason: ${drop.reason}, " +
    "transport: ${drop.transport}, " +
    "device: ${drop.device}, " +
    "initialState: ${drop.initialState}, " +
    "finalState: $finalState, " +
    "intent: ${drop.intent}" +
    ")"

internal fun dumpDeviceHandle(handle: String?): String = handle?.let { deviceHandlePrefix(it) } ?: "none"

package one.meshcore.shared.transport

/**
 * iOS `handleDidDisconnect` / `handleConnectionLoss` dump lines. A radio-drop export must carry the
 * disconnect itself, not only the last-disconnect diagnostic field.
 */
internal fun deviceHandlePrefix(handle: String): String = handle.take(HANDLE_PREFIX_LENGTH)

internal fun bleDidDisconnectLine(snapshot: BleDisconnectSnapshot): String =
    "[BLE] Did disconnect: ${deviceHandlePrefix(snapshot.handle)}, " +
        "peripheralState: ${snapshot.peripheralState}, " +
        "isReconnecting: ${snapshot.isReconnecting}, " +
        "phase: ${snapshot.phase}, " +
        "elapsed: ${snapshot.elapsedSeconds}s, " +
        "error: ${snapshot.error}"

internal fun bleConnectionLostLine(handle: String, currentState: String, error: String): String =
    "[BLE] Connection lost: ${deviceHandlePrefix(handle)}, currentState: $currentState, error: $error"

internal fun bleStateDisconnectedOnLossLine(handle: String): String =
    "[BLE] State → .disconnected (connection loss for device: ${deviceHandlePrefix(handle)})"

internal const val BLE_DISCONNECT_STATE_LINE = "[BLE] disconnect: state → .disconnected"

private const val HANDLE_PREFIX_LENGTH: Int = 8

internal fun formatElapsedSeconds(seconds: Double): String {
    val scaled = (seconds * ELAPSED_SCALE).toInt()
    val whole = scaled / ELAPSED_SCALE
    val frac = scaled % ELAPSED_SCALE
    return "$whole.${frac.toString().padStart(ELAPSED_FRACTION_DIGITS, '0')}"
}

private const val ELAPSED_FRACTION_DIGITS: Int = 2
private const val ELAPSED_SCALE: Int = 100

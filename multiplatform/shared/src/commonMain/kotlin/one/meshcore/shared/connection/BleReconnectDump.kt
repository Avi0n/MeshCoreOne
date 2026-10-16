package one.meshcore.shared.connection

import one.meshcore.shared.transport.deviceHandlePrefix

/** iOS `[BLE] Watchdog attempting reconnection`. */
const val BLE_WATCHDOG_ATTEMPTING = "[BLE] Watchdog attempting reconnection"

/** iOS `Connection complete - device ready`. */
const val BLE_CONNECTION_COMPLETE = "Connection complete - device ready"

/** The connect sheet cancelled after Ready; the live session stays up. */
const val BLE_CONNECT_CANCEL_AFTER_READY =
    "[BLE] Connect caller cancelled after Ready; leaving live session"

/** iOS `[BLE] Skipping foreground reconnect: reconnect/session rebuild already in progress`. */
const val BLE_FOREGROUND_SKIP_IN_PROGRESS =
    "[BLE] Skipping foreground reconnect: reconnect/session rebuild already in progress"

fun bleReconnectAttemptFailedLine(attempt: Int, error: String): String =
    "[BLE] Reconnection attempt $attempt failed - error: $error"

fun bleReconnectionSucceededLine(attempt: Int): String = "Reconnection succeeded on attempt $attempt"

fun bleForegroundAttemptingLine(handle: String): String =
    "[BLE] Attempting foreground reconnection to ${deviceHandlePrefix(handle)}"

fun blePoweredOnAttemptingLine(handle: String): String =
    "[BLE] Bluetooth powered on: attempting reconnection to ${deviceHandlePrefix(handle)}"

fun bleBluetoothStateChangedLine(enabled: Boolean): String =
    "[BLE] Bluetooth state changed: ${if (enabled) "poweredOn" else "poweredOff"}"

/** iOS `[BLE] Skipping foreground reconnect: Bluetooth is powered off`. */
const val BLE_FOREGROUND_SKIP_POWERED_OFF = "[BLE] Skipping foreground reconnect: Bluetooth is powered off"

/** iOS `[BLE] Skipping foreground reconnect: device connected to another app`. */
const val BLE_FOREGROUND_SKIP_OTHER_APP =
    "[BLE] Skipping foreground reconnect: device connected to another app"

/** iOS `[BLE] Watchdog skipping: Bluetooth powered off`. */
const val BLE_WATCHDOG_SKIP_POWERED_OFF = "[BLE] Watchdog skipping: Bluetooth powered off"

/** iOS `[BLE] Opportunistic reconnect skipped (...): pairing in progress`. */
const val BLE_OPPORTUNISTIC_SKIP_PAIRING =
    "[BLE] Opportunistic reconnect skipped (pairing): pairing in progress"

fun bleMissingListenersRestartLine(radioPrefix: String): String =
    "[BLE] Connected BLE app stack missing listeners; restarting event pipeline for $radioPrefix"

/** iOS `[BLE] Detected stale connection state on foreground: connectionState=… but BLE disconnected…`. */
fun bleForegroundStaleConnectionLine(stateName: String): String =
    "[BLE] Detected stale connection state on foreground: connectionState=$stateName " +
        "but BLE disconnected, triggering cleanup"

/** iOS `[BLE] {context}: device appears system-connected while disconnected; attempting adoption`. */
fun bleAdoptSystemConnectedLine(context: String, handlePrefix: String): String =
    "[BLE] $context: device appears system-connected while disconnected; attempting adoption - device=$handlePrefix"

/** iOS `[BLE] Connected BLE transport has missing app stack; rebuilding session for`. */
fun bleConnectedMissingAppStackLine(handlePrefix: String): String =
    "[BLE] Connected BLE transport has missing app stack; rebuilding session for $handlePrefix"

package one.meshcore.shared.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * iOS `DeviceSelectionSheet` “Connected elsewhere”: a BLE-capable saved row whose handle is in
 * the phone’s GATT connected set while this app is disconnected. WiFi-only rows are skipped.
 * A live session (Change Device while linked) never reports elsewhere — that would flag our own link.
 */
fun savedDeviceIsConnectedElsewhere(
    device: SavedRadioDevice,
    connectionState: DeviceConnectionState,
    systemConnectedHandles: Set<String>,
): Boolean {
    val handle = device.bleHandle ?: return false
    return bleHandleIsHeldByOtherApp(handle, connectionState, systemConnectedHandles)
}

/**
 * Whether [handle] is in this phone’s GATT connected set while MeshCore is disconnected.
 * Letter case is ignored — Android MAC strings and Kable ids disagree on case.
 */
fun bleHandleIsHeldByOtherApp(
    handle: String,
    connectionState: DeviceConnectionState,
    systemConnectedHandles: Set<String>,
): Boolean {
    if (connectionState != DeviceConnectionState.DISCONNECTED) return false
    val key = handle.trim().takeIf { it.isNotEmpty() } ?: return false
    return systemConnectedHandles.any { it.equals(key, ignoreCase = true) }
}

/** iOS `defaultWaitForOtherAppReconnection` after ASK severs another app’s GATT. */
const val OTHER_APP_RECONNECTION_MAX_CHECKS = 6

/** Spacing between GATT polls while the other app’s auto-reconnect lands. */
const val OTHER_APP_RECONNECTION_INTERVAL_MS = 400L

/**
 * Polls whether another app reclaimed the radio after Companion Device Manager
 * (iOS ASK) dropped its link. Six checks, 400ms apart. Cancel short-circuits
 * to false so the caller’s connect path can surface cancellation instead of
 * connecting. The connect path still does a one-shot GATT snapshot; this wait
 * is what catches the other app coming back a second later.
 */
suspend fun waitForOtherAppReconnection(
    isHeldNow: suspend () -> Boolean,
    isCancelled: () -> Boolean = { false },
    maxChecks: Int = OTHER_APP_RECONNECTION_MAX_CHECKS,
    intervalMs: Long = OTHER_APP_RECONNECTION_INTERVAL_MS,
    delayMs: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    for (check in 1..maxChecks) {
        val seen = otherAppReclaimed(isHeldNow, isCancelled)
        if (seen != null) return seen
        if (check < maxChecks) delayMs(intervalMs)
    }
    return false
}

private suspend fun otherAppReclaimed(isHeldNow: suspend () -> Boolean, isCancelled: () -> Boolean): Boolean? {
    if (isCancelled() || !currentCoroutineContext().isActive) return false
    if (isHeldNow()) return true
    return null
}

/**
 * iOS `connect(to:)` throws `deviceConnectedToOtherApp` for a BLE-held radio that is not
 * the last-connected one. The last radio tries to adopt the existing system link first.
 * A WiFi-capable saved row skips this and uses TCP, matching iOS `connectToDevice`.
 */
fun shouldPresentOtherAppInsteadOfConnect(isHeldByOtherApp: Boolean, isLastConnectedRadio: Boolean): Boolean =
    isHeldByOtherApp && !isLastConnectedRadio

/**
 * Saved-row tap: refuse BLE when another app holds the radio, except the last-connected
 * adopt path. A WiFi-capable row always proceeds (iOS `connectToDevice` uses TCP first).
 */
fun shouldRefuseSavedConnectForOtherApp(
    connectsViaWiFi: Boolean,
    isHeldByOtherApp: Boolean,
    isLastConnectedRadio: Boolean,
): Boolean = !connectsViaWiFi &&
    shouldPresentOtherAppInsteadOfConnect(isHeldByOtherApp, isLastConnectedRadio)

/**
 * Health-check and the BLE watchdog: skip a new connect while another app holds [handle],
 * except the last radio (that path adopts via a direct GATT connect).
 */
fun shouldDeferReconnectBecauseOtherApp(
    handle: String,
    lastHandle: String?,
    connectionState: DeviceConnectionState,
    systemConnectedHandles: Set<String>,
): Boolean {
    val held = bleHandleIsHeldByOtherApp(handle, connectionState, systemConnectedHandles)
    val last = lastHandle?.trim()?.takeIf { it.isNotEmpty() }
    val isLast = last != null && last.equals(handle.trim(), ignoreCase = true)
    return shouldPresentOtherAppInsteadOfConnect(held, isLast)
}

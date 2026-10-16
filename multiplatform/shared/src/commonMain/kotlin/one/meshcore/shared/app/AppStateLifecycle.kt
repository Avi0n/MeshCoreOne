package one.meshcore.shared.app

import kotlinx.coroutines.launch
import one.meshcore.shared.connection.BLE_LIFECYCLE_BACKGROUNDED_COMPLETE_LINE
import one.meshcore.shared.connection.bleLifecycleActiveCompleteLine
import one.meshcore.shared.connection.bleLifecycleBecomingActiveLine
import one.meshcore.shared.connection.bleLifecycleEnteringBackgroundLine
import one.meshcore.shared.connection.connectionIntentSummary
import one.meshcore.shared.connection.lifecycleStateName
import one.meshcore.shared.connection.lifecycleTransportName
import one.meshcore.shared.connection.setAppActive
import one.meshcore.shared.contact.handleReturnToForeground
import one.meshcore.shared.remotenode.stopAllKeepAlives

/**
 * Foreground reconcile. Mirrors Swift `checkWiFiConnectionHealth` case 1 (probe a still-operational
 * WiFi session immediately) and `checkBLEConnectionHealth` (tear down a Ready session whose GATT
 * died while backgrounded). A BLE radio then gets the Swift `appDidBecomeActive` re-arm: a link
 * that dropped and gave up while the app was off screen leaves the radio wanted with no loop
 * running, and the arm is self-guarded so a live WiFi connection or an already-running loop is
 * left alone.
 */
suspend fun AppStateViewModel.onEnterForeground() {
    nodeDiscoveryWorkspace?.expireDeadline()
    tracePathWorkspace?.expireDeadline()
    remoteAdminWorkspaces?.expireDeadline()
    connectionManager.setAppActive(true)
    connectionManager.services?.contactService?.handleReturnToForeground()
    if (intentStore.restore().isUserDisconnected) return
    val intent = connectionManager.connectionIntentSummary()
    val stateName = lifecycleStateName(connectionManager.state.value)
    logger.info(
        bleLifecycleBecomingActiveLine(
            lifecycleTransportName(connectionManager.currentTransportType),
            intent,
            stateName,
        ),
    )
    try {
        ForegroundReconcile().run()
    } finally {
        logger.info(bleLifecycleActiveCompleteLine(lifecycleStateName(connectionManager.state.value)))
    }
}

/**
 * Backgrounding hook. The connection itself lives on (the foreground service owns it), but the room
 * keep-alives must not: each one is a radio transmit on a timer, and a backgrounded app has no room on
 * screen to keep alive. Mirrors iOS `AppState.handleEnterBackground`, which stops them for the same
 * battery and airtime reason. Reopening a room restarts its keep-alive.
 */
fun AppStateViewModel.onEnterBackground() {
    remoteAdminWorkspaces?.pauseDiscoveryPolls()
    connectionManager.setAppActive(false)
    scope.launch {
        logger.info(
            bleLifecycleEnteringBackgroundLine(
                lifecycleTransportName(connectionManager.currentTransportType),
                connectionManager.connectionIntentSummary(),
                lifecycleStateName(connectionManager.state.value),
            ),
        )
        connectionManager.services?.remoteNodeService?.stopAllKeepAlives()
        logger.info(BLE_LIFECYCLE_BACKGROUNDED_COMPLETE_LINE)
    }
}

package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.bleConnectionLostLine
import one.meshcore.shared.transport.bleStateDisconnectedOnLossLine
import one.meshcore.shared.transport.deviceHandlePrefix

internal suspend fun ConnectionManager.handleLinkDrop(
    deviceId: String,
    reason: LinkDropReason,
    generation: Long,
    boundSession: MeshSession? = null,
) {
    // Reconnect keeps the same handle, so deviceId alone cannot reject a delayed drop from the
    // GATT this attempt replaced. Inner retries also keep the generation, so the bound session
    // has to match too.
    if (staleLinkDrop(generation, boundSession, deviceId)) {
        logger.info(
            bleIgnoringConnectionLossLine(deviceHandlePrefix(deviceId), deviceHandlePrefix(currentDeviceId ?: "")),
        )
        return
    }
    if (currentTransportType == TransportType.WIFI) {
        logger.warning(wifiConnectionLostLine(reason.name))
    } else {
        logger.warning(
            bleConnectionLostLine(
                handle = deviceId,
                currentState = connectionStateDumpName(mutableState.value),
                error = reason.name,
            ),
        )
        logger.warning(bleStateDisconnectedOnLossLine(deviceId))
    }
    if (mutableState.value is ConnectionState.Syncing) {
        logger.warning(SYNC_IN_PROGRESS_AT_DISCONNECT)
    }
    persistDisconnectDiagnostic(
        "source=linkDrop, " +
            "reason=${reason.name}, " +
            "transport=${currentTransportType?.name ?: ConnectionManager.NO_VALUE}, " +
            "device=$deviceId, " +
            "intent=${connectionIntentSummary()}",
    )
    if (staleLinkDrop(generation, boundSession, deviceId)) return
    // Capture before teardown, and union with whatever an earlier drop queued but no reconnect got to
    // replay, so an admin session open across a drop is re-authenticated instead of silently dying.
    val remoteSessions = captureRemoteSessionsForReauth()
    if (staleLinkDrop(generation, boundSession, deviceId)) return
    pendingReauthSessionIds = pendingReauthSessionIds + remoteSessions
    val outcomeGeneration = tearDownCurrent(stopSession = false)
    if (ownsConnectAttempt(outcomeGeneration) && currentDeviceId == deviceId) {
        logger.info(SYNC_STATE_IDLE_DISCONNECTED)
        logger.info(SYNC_DISCONNECTED_RESET)
        applyLinkDropOutcome(deviceId, reason, outcomeGeneration)
    }
}

// Bond loss still starts the watchdog unless pairing owns the radio. A forgotten radio, a user
// disconnect, and an in-progress pair all stop at Disconnected. Wi-Fi needs both host and port.
private suspend fun ConnectionManager.applyLinkDropOutcome(
    deviceId: String,
    reason: LinkDropReason,
    generation: Long,
) {
    if (reason == LinkDropReason.BOND_LOST) mutableState.value = ConnectionState.BondLost(deviceId)
    val forgotten = wantsConnection && lastConnectionForgotten()
    if (ownsConnectAttempt(generation) && currentDeviceId == deviceId) {
        finishLinkDropOutcome(deviceId, reason, forgotten)
    }
}

private fun ConnectionManager.finishLinkDropOutcome(deviceId: String, reason: LinkDropReason, forgotten: Boolean) {
    when {
        reason == LinkDropReason.BOND_LOST -> {
            // iOS surfaces guided recovery then still starts the watchdog: a false-positive
            // GATT auth status can succeed on the next tick. Pairing owns the radio instead.
            val userStillWantsRadio = wantsConnection && !forgotten
            if (userStillWantsRadio &&
                currentTransportType != TransportType.WIFI &&
                !pairingInProgress
            ) {
                startBleReconnect(deviceId)
            }
        }
        !wantsConnection || forgotten -> mutableState.value = ConnectionState.Disconnected
        currentTransportType == TransportType.WIFI ->
            startWiFiReconnect(wifiHost ?: return, wifiPort ?: return, deviceId)
        pairingInProgress -> {
            logger.info(BLE_OPPORTUNISTIC_SKIP_PAIRING)
            mutableState.value = ConnectionState.Disconnected
        }
        else -> startBleReconnect(deviceId)
    }
}

private fun ConnectionManager.staleLinkDrop(generation: Long, boundSession: MeshSession?, deviceId: String): Boolean {
    val attemptMismatch = !ownsConnectAttempt(generation) ||
        (boundSession != null && session !== boundSession)
    return attemptMismatch || currentDeviceId != deviceId
}

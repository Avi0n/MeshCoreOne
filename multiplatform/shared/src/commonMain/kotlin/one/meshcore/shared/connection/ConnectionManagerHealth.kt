package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.deviceHandlePrefix

/**
 * Foreground resync of a live radio whose contacts phase has already failed. iOS
 * `checkSyncHealth` waits for `syncCoordinator.state == .failed`; it is a no-op while the
 * first full sync is still `.syncing`. A [ConnectionState.Ready] radio is left alone.
 */
fun ConnectionManager.checkSyncHealth() {
    if (!wantsConnection) return
    val handle = currentDeviceId ?: return
    val container = services ?: return
    if (!mutableState.value.toDeviceConnectionState().isOperational) return
    if (initialSyncInProgress) return
    if (resyncJob?.isActive == true) {
        logger.info(RESYNC_SKIP_FOREGROUND_TRIGGER)
        return
    }
    if (mutableState.value !is ConnectionState.Syncing) return
    logger.info(FOREGROUND_SYNC_FAILED_START_RESYNC)
    startResyncLoop(
        handle,
        container,
        container.radioId,
        forceFullSync = pendingForceFullSync,
        generation = connectGeneration,
    )
}

/**
 * Foreground WiFi liveness. iOS `checkWiFiConnectionHealth` case 1: if the session is still
 * operational but the companion died while backgrounded, probe immediately rather than waiting
 * out the heartbeat interval. A failed probe drops the link so reconnect can start.
 */
suspend fun ConnectionManager.checkWiFiConnectionHealth() {
    if (currentTransportType != TransportType.WIFI) return
    if (!mutableState.value.toDeviceConnectionState().isOperational) return
    wifiHeartbeatProbe(fromForeground = true)
}

/**
 * Foreground BLE liveness. iOS `checkBLEConnectionHealth`: rebuild a live GATT whose session
 * or services are missing, restart dead listeners on a healthy stack, tear down a Ready
 * session whose GATT died while backgrounded, adopt a system-connected last radio while idle,
 * skip a fight when another app still holds the handle, then opportunistic `connect`.
 */
suspend fun ConnectionManager.checkBLEConnectionHealth() {
    if (currentTransportType == TransportType.WIFI) return
    if (pairingInProgress) return
    if (!wantsConnection) return
    val deviceId = currentDeviceId ?: lastConnectionStore?.handle() ?: return
    if (isBleReconnectInProgress()) return
    if (link?.isConnected == true) {
        if (shouldRebuildConnectedBleAppStack(
                linkConnected = true,
                hasSession = session != null,
                hasServices = services != null,
                reconnectInProgress = false,
                connectCeremonyInFlight = mutableState.value is ConnectionState.Connecting,
            )
        ) {
            rebuildConnectedBleAppStack(deviceId)
        } else {
            reconcileConnectedListeners()
        }
        return
    }
    recoverDisconnectedBle(deviceId)
}

/**
 * Link is down. A dead adapter stops here. A Ready session whose GATT died drops first, then a
 * system-connected last radio is adopted, and another app holding the handle skips connect.
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun ConnectionManager.recoverDisconnectedBle(deviceId: String) {
    if (!bluetoothAdapterEnabled()) return
    if (mutableState.value.toDeviceConnectionState().isConnected) {
        logger.warning(bleForegroundStaleConnectionLine(connectionStateDumpName(mutableState.value)))
        handleLinkDrop(deviceId, LinkDropReason.CONNECTION_LOST, connectGeneration, session)
    }
    if (startAdoptingLastSystemConnectedIfAvailable("checkBLEConnectionHealth", deviceId)) return
    val lastHandle = lastConnectionStore?.handle() ?: currentDeviceId
    if (shouldDeferReconnectBecauseOtherApp(
            handle = deviceId,
            lastHandle = lastHandle,
            connectionState = mutableState.value.toDeviceConnectionState(),
            systemConnectedHandles = systemConnectedHandles(),
        )
    ) {
        persistDisconnectDiagnostic(
            "source=checkBLEConnectionHealth.otherAppConnected, " +
                "device=$deviceId, " +
                "intent=${connectionIntentSummary()}",
        )
        logger.info(BLE_FOREGROUND_SKIP_OTHER_APP)
        return
    }
    logger.info(bleForegroundAttemptingLine(deviceId))
    try {
        connect(deviceId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger.warning(
            "[BLE] Opportunistic reconnect (foreground health check) failed: ${error.message ?: error}",
        )
    }
}

/**
 * iOS `rebuildConnectedBLEAppStack`: handshake over the live GATT and rebuild [ServiceContainer]
 * without issuing a new connect.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConnectionManager.rebuildConnectedBleAppStack(deviceId: String) {
    val liveLink = link ?: return
    if (!liveLink.isConnected) return
    logger.warning(bleConnectedMissingAppStackLine(deviceHandlePrefix(deviceId)))
    mutableState.value = ConnectionState.Connecting(deviceId)
    val generation = connectGeneration
    try {
        val (selfInfo, capabilities) = handshakeOverExistingLink(deviceId, liveLink, generation)
        if (!ownsConnectAttempt(generation)) return
        completeBleAfterHandshake(deviceId, generation, selfInfo, capabilities)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger.warning("[BLE] Connected-transport session rebuild failed: ${error.message ?: error}")
        if (ownsConnectAttempt(generation)) {
            handleLinkDrop(deviceId, LinkDropReason.CONNECTION_LOST, generation, session)
        }
    }
}

/**
 * Foreground recovery for a live BLE radio whose event pipeline or auto-fetch died while the
 * transport stayed up. iOS `reconcileConnectedBLEListeners`. Auto-fetch restarts only at Ready
 * so a Syncing window cannot lose backlog to a second drain.
 */
suspend fun ConnectionManager.reconcileConnectedListeners() {
    if (!wantsConnection) return
    if (currentTransportType == TransportType.WIFI) return
    val container = services ?: return
    val deviceState = mutableState.value.toDeviceConnectionState()
    if (!deviceState.isOperational) return
    val enableAutoFetch = mutableState.value is ConnectionState.Ready
    if (container.isEventMonitoringActive && (container.isAutoFetching || !enableAutoFetch)) return
    logger.warning(bleMissingListenersRestartLine(deviceHandlePrefix(container.radioId.value)))
    container.liveSync.reconcileLiveListeners(
        connectionStates = state.map { it.toDeviceConnectionState() },
        initialState = deviceState,
        enableAutoFetch = enableAutoFetch,
    )
}

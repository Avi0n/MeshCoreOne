package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.simulator.MockDataProvider
import one.meshcore.shared.transport.TcpTransportException

/** iOS `appDidBecomeActive` / `appDidEnterBackground` for reconnect classification. */
fun ConnectionManager.setAppActive(active: Boolean) {
    scenePhaseKnown = true
    isAppActive = active
}

/**
 * Connects to [deviceId] over BLE: establishes the link, runs the session handshake, builds the
 * container, and syncs contacts, ending at [ConnectionState.Ready]. On a bond failure it ends at
 * [ConnectionState.BondLost]; any other failure ends at [ConnectionState.Disconnected] and rethrows.
 */
suspend fun ConnectionManager.connect(
    deviceId: String,
    forceFullSync: Boolean = false,
    forceReconnect: Boolean = false,
) {
    requireBleConnectAuthorization(deviceId)
    allowConnectOrThrow(forceReconnect, deviceId)
    if (state.value.deviceId == deviceId && state.value.isLinkUp) return
    // Launch activate and a sticky foreground-service restart both auto-connect the last radio; the
    // second joins the attempt already in flight rather than tearing it down mid-GATT (or mid-PIN).
    // iOS `connect(to:)` also joins a same-device Connecting/reconnect when the user taps
    // (`forceReconnect`); it only abandons a stuck Disconnected watchdog.
    if (shouldJoinInFlightBleConnect(
            InFlightBleJoin(
                forceReconnect = forceReconnect,
                connectingSameDevice = state.value == ConnectionState.Connecting(deviceId),
                reconnectInProgressForSameDevice = isBleReconnectInProgress() && currentDeviceId == deviceId,
                connectionIsDisconnected = state.value is ConnectionState.Disconnected,
                sessionRebuildOwnsHandle = sessionRebuildDeviceId == deviceId,
                connectionIsBondLost = state.value is ConnectionState.BondLost,
            ),
        )
    ) {
        applyJoinInFlightConnect(forceFullSync)
        return
    }
    val generation = beginConnect(TransportType.BLUETOOTH, handle = deviceId, forceFullSync = forceFullSync)
    throwIfSuperseded(generation)
    wifiHost = null
    wifiPort = null
    mutableState.value = ConnectionState.Connecting(deviceId)
    logger.info(connectingToDeviceLine(deviceId))
    runBleConnectAttempts(deviceId, generation, forceReconnect)
}

/**
 * Connects to a radio over WiFi/TCP at [host]:[port]. The device row id is derived from the public
 * key (WiFi radios have no Bluetooth handle), the reachability descriptor is persisted as a
 * [ConnectionMethod.WiFi], and a heartbeat + WiFi reconnect schedule arm on success.
 *
 * WiFi never consults the BLE circuit breaker (iOS `connectViaWiFi`).
 */
suspend fun ConnectionManager.connectViaWiFi(host: String, port: Int, forceFullSync: Boolean = false) {
    val host = host.trim()
    if (host.isEmpty()) throw TcpTransportException.InvalidHost()
    logger.info(wifiConnectingLine(host, port))
    val handle = wifiHandle(host, port)
    // iOS `shouldAllowConnection` / `recordConnectionFailure` are BLE `connect(to:)` only.
    // An open BLE cooldown must not reject a WiFi reconnect of the last radio.
    val generation = beginConnect(TransportType.WIFI, handle = handle, forceFullSync = forceFullSync)
    throwIfSuperseded(generation)
    wifiHost = host
    wifiPort = port
    mutableState.value = ConnectionState.Connecting(handle)
    runWifiConnect(host, port, handle, generation)
}

@Suppress("TooGenericExceptionCaught")
private suspend fun ConnectionManager.runWifiConnect(host: String, port: Int, handle: String, generation: Long) {
    try {
        wifiConnectCeremony(host, port, handle, generation)
        logger.info(WIFI_CONNECTION_COMPLETE)
    } catch (cancelled: CancellationException) {
        abandonCancelledConnect(generation)
        throw cancelled
    } catch (error: Throwable) {
        abandonFailedWifiConnect(handle, generation)
        throw error
    }
}

private suspend fun ConnectionManager.abandonFailedWifiConnect(handle: String, generation: Long) {
    // A superseded attempt owns nothing; see [connect].
    if (!ownsConnectAttempt(generation)) return
    val expectedHandle = currentDeviceId
    val expectedTransport = currentTransportType
    val expectedHost = wifiHost
    val expectedPort = wifiPort
    val expectedWants = wantsConnection
    val retiredGeneration = tearDownCurrent(stopSession = false)
    fun ownsFailureExit(): Boolean = ownsEmptyWifiFailureGraph(retiredGeneration) &&
        currentDeviceId == expectedHandle &&
        currentTransportType == expectedTransport &&
        wifiHost == expectedHost &&
        wifiPort == expectedPort &&
        wantsConnection == expectedWants
    if (!ownsFailureExit()) return
    // Drop the WIFI stamp so BLE adopt / health-check / watchdog can run while
    // wantsConnection stays true. Restore the last BLE handle so health-check
    // does not aim GATT at this WiFi host:port.
    val lastHandle = if (expectedHandle == handle) lastConnectionStore?.handle() else null
    if (!ownsFailureExit()) return
    if (expectedHandle == handle) currentDeviceId = lastHandle?.takeUnless { it == handle }
    currentTransportType = null
    wifiHost = null
    wifiPort = null
    mutableState.value = ConnectionState.Disconnected
}

private fun ConnectionManager.ownsEmptyWifiFailureGraph(generation: Long): Boolean =
    ownsConnectAttempt(generation) && services == null && session == null && link == null

/**
 * Connects the bundled simulator/demo radio and seeds the store, matching iOS
 * `ConnectionManager.simulatorConnect`. There is no handshake or full sync: the session is a
 * placeholder and reviewers browse the seeded catalog.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun ConnectionManager.simulatorConnect() {
    val handle = MockDataProvider.SIMULATOR_HANDLE
    val generation = beginConnect(TransportType.BLUETOOTH, handle = handle, forceFullSync = false)
    throwIfSuperseded(generation)
    mutableState.value = ConnectionState.Connecting(handle)
    try {
        openSimulatorSession(handle, generation)
    } catch (cancelled: CancellationException) {
        abandonCancelledConnect(generation)
        throw cancelled
    } catch (error: Throwable) {
        if (ownsConnectAttempt(generation)) {
            tearDownCurrent(stopSession = false)
            mutableState.value = ConnectionState.Disconnected
        }
        throw error
    }
}

/**
 * Disconnects from the radio. A [DisconnectReason.USER_INITIATED] disconnect clears the connection
 * intent (persisting the user-disconnected bit) so auto-reconnect stays down — the load-bearing
 * distinction from a connection loss, which does arm reconnect. Every other reason preserves the intent
 * so the radio stays wanted, mirroring the Swift `disconnect(reason:)` switch.
 */
suspend fun ConnectionManager.disconnect(reason: DisconnectReason = DisconnectReason.USER_INITIATED) {
    // Captured before the intent clear nulls them; the diagnostic is about the connection being left.
    val handle = currentDeviceId
    val transport = currentTransportType
    val initialState = connectionStateDumpName(mutableState.value)
    val intentBefore = connectionIntentSummary()
    logger.info(
        disconnectingFromDeviceLine(
            reason = reason.dumpLabel(),
            transport = transport.dumpName(),
            device = dumpDeviceHandle(handle),
            initialState = initialState,
            intent = intentBefore,
        ),
    )
    if (reason.clearsUserIntent) {
        wantsConnection = false
        currentDeviceId = null
        currentTransportType = null
        wifiHost = null
        wifiPort = null
        lastCleanChannelSync = null
        lastAttemptedChannelSync = null
    }
    if (mutableState.value is ConnectionState.Syncing) {
        logger.warning(SYNC_IN_PROGRESS_AT_DISCONNECT)
    }
    cancelReconnect()
    cancelResyncLoop()
    cancelChannelRetry()
    val cleanup = DisconnectCleanup(this)
    val captureCancelled = captureTearDownCancellation { clearRemoteReauthQueue() }
    cleanup.complete(reason, captureCancelled) {
        recordDisconnectCompletion(reason, transport, handle, initialState)
    }
}

private suspend fun ConnectionManager.recordDisconnectCompletion(
    reason: DisconnectReason,
    transport: TransportType?,
    handle: String?,
    initialState: String,
) {
    persistDisconnectDiagnostic(
        "source=disconnect, " +
            "reason=${reason.name}, " +
            "transport=${transport?.name ?: ConnectionManager.NO_VALUE}, " +
            "device=${handle ?: ConnectionManager.NO_VALUE}, " +
            "intent=${connectionIntentSummary()}",
    )
    logger.info(
        disconnectedLine(
            drop = ConnectionDropContext(
                reason = reason.dumpLabel(),
                transport = transport.dumpName(),
                device = dumpDeviceHandle(handle),
                initialState = initialState,
                intent = connectionIntentSummary(),
            ),
            finalState = connectionStateDumpName(mutableState.value),
        ),
    )
}

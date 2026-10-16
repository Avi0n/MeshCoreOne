package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.SelfInfo

// Stamps a summary with the manager's own clock, so the diagnostic orders against the debug-log rows
// it ships beside. Both are epoch values, not wall-clock text: the shared module carries no date
// formatter, and a diagnostic that lies about its zone is worse than one that needs converting.
internal suspend fun ConnectionManager.persistDisconnectDiagnostic(summary: String) {
    applyLastConnectionPersist(
        persist = {
            lastConnectionStore?.persistDisconnectDiagnostic(summary, nowEpochSeconds().toString())
        },
        logger = logger,
        failLine = FAILED_PERSIST_DISCONNECT_DIAGNOSTIC,
    )
}

// Shared entry bookkeeping for both transports: arm intent (clearing the user-disconnected bit),
// target the handle, and drop any prior connection before the fresh attempt.
// Returns the generation this attempt owns, captured at the supersede bump itself: the teardown and
// intent persist that follow suspend, and a second connect landing in that window bumps again. Reading
// the counter after beginConnect returns would hand both attempts the same generation, and both would
// then pass the fence and tear each other's link down as a failed retry.
internal suspend fun ConnectionManager.beginConnect(
    transportType: TransportType,
    handle: String,
    forceFullSync: Boolean = false,
): Long {
    wantsConnection = true
    pendingForceFullSync = forceFullSync
    currentDeviceId = handle
    currentTransportType = transportType
    cancelReconnect()
    clearRemoteReauthQueue()
    val generation = tearDownCurrent(stopSession = true)
    persistConnectionIntent(ConnectionIntent.WantsConnection(forceFullSync))
    return generation
}

/** iOS join of an in-flight same-device connect: keep GATT, adopt the caller's full-sync intent. */
internal suspend fun ConnectionManager.applyJoinInFlightConnect(forceFullSync: Boolean) {
    wantsConnection = true
    pendingForceFullSync = forceFullSync
    persistConnectionIntent(ConnectionIntent.WantsConnection(forceFullSync))
    val deviceId = currentDeviceId ?: return
    // iOS skips `restartTimeout` while `rebuildSession` still owns the handle.
    if (sessionRebuildDeviceId != deviceId) restartReconnectUiTimeout(deviceId)
}

internal suspend fun ConnectionManager.persistConnectionIntent(intent: ConnectionIntent) {
    applyLastConnectionPersist(
        persist = { intentStore?.persist(intent) },
        logger = logger,
        failLine = FAILED_PERSIST_CONNECTION_INTENT,
    )
}

// The caller sets the entering state (Connecting for a fresh connect, Reconnecting for the loop) so
// a reconnect stays visibly Reconnecting until the link is up, rather than flipping to Connecting.
internal suspend fun ConnectionManager.bleConnectCeremony(
    deviceId: String,
    generation: Long,
    autoReconnect: Boolean = false,
) {
    if (!bluetoothConnectAuthorized()) throw one.meshcore.shared.transport.BluetoothUnauthorizedException()
    val newLink = if (autoReconnect) reconnectLinkFactory(deviceId) else linkFactory(deviceId)
    val (selfInfo, capabilities) = openLinkAndHandshake(deviceId, newLink)
    completeBleAfterHandshake(deviceId, generation, selfInfo, capabilities)
}

internal suspend fun ConnectionManager.completeBleAfterHandshake(
    deviceId: String,
    generation: Long,
    selfInfo: SelfInfo,
    capabilities: DeviceCapabilities,
) {
    val rowId = StableIds.uuid(deviceId.encodeToByteArray())
    finishConnection(
        deviceId,
        rowId,
        HandshakeIdentity(selfInfo, capabilities, bleReachability(rowId, deviceId)),
        generation,
    )
    if (!ownsConnectAttempt(generation)) return
    // A completed handshake proves this bond just carried an encrypted session; stamp it in memory for
    // the live grace and in the store so the grace survives a cold rebuild. A later reconnect's
    // encryption-timeout budget is graced off this stamp; fresh connect and reconnect rebuild both land
    // here. WiFi never reaches this path, matching Swift (WiFi carries no bond).
    val verifiedAt = nowEpochSeconds()
    bondVerificationEpochSeconds[deviceId] = verifiedAt
    applyLastConnectionPersist(
        persist = { lastConnectionStore?.persistBondVerification(deviceId, verifiedAt) },
        logger = logger,
        failLine = FAILED_PERSIST_BOND_VERIFICATION,
    )
    startBondRefreshLoop(deviceId)
}

internal suspend fun ConnectionManager.wifiConnectCeremony(host: String, port: Int, handle: String, generation: Long) {
    val newLink = wifiLinkFactory(host, port) { wifiHeartbeatProbe() }
    val (selfInfo, capabilities) = openLinkAndHandshake(handle, newLink)
    val rowId = deriveWifiDeviceId(selfInfo.publicKey)
    val reachability = listOf(ConnectionMethod.WiFi(host, port, null))
    finishConnection(handle, rowId, HandshakeIdentity(selfInfo, capabilities, reachability), generation)
}

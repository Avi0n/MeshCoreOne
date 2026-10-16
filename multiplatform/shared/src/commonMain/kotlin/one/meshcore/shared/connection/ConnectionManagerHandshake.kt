package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.transport.LinkDropReason
import one.meshcore.shared.transport.MeshLinkConnectException
import one.meshcore.shared.transport.deviceHandlePrefix

/** Stops the app stack and leaves GATT installed so a session rebuild can reuse it. */
internal suspend fun ConnectionManager.dropSessionKeepingLink() {
    val generation = connectGeneration
    val oldServices = services
    val oldSession = session
    val oldLink = link
    val teardownCancelled = captureTearDownCancellation { oldServices?.tearDown() }
    val resourcesMatch = services === oldServices && session === oldSession && link === oldLink
    var stopCancelled: CancellationException? = null
    if (ownsConnectAttempt(generation) && resourcesMatch) {
        services = null
        session = null
        if (oldSession != null) {
            stopCancelled = captureSessionStop(logger::warning) { oldSession.stop() }
            oldSession.close()
        }
    }
    (teardownCancelled ?: stopCancelled)?.let { throw it }
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> ConnectionManager.awaitHandshakeStep(operationName: String, block: suspend () -> T): T {
    val response = try {
        withTimeoutOrNull(handshakeTimeout) { Result.success(block()) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        rethrowHandshakeStepFailure(operationName, error)
    }
    return if (response != null) {
        response.getOrThrow()
    } else {
        rethrowHandshakeStepFailure(operationName, MeshLinkConnectException("$operationName timed out"))
    }
}

private fun ConnectionManager.rethrowHandshakeStepFailure(operationName: String, error: Throwable): Nothing {
    logger.warning("[BLE] $operationName timed out or failed: ${error.message ?: error.toString()}")
    throw error
}

internal fun ConnectionManager.allowConnectOrThrow(forceReconnect: Boolean, handle: String) {
    val before = circuitBreaker.state
    if (!circuitBreaker.shouldAllow(forceReconnect)) {
        logger.info(
            "[BLE] Circuit breaker open, rejecting connection to ${deviceHandlePrefix(handle)}",
        )
        throw MeshLinkConnectException(ConnectionCircuitBreaker.BLOCKED_MESSAGE)
    }
    if (before is ConnectionCircuitBreaker.State.Open &&
        circuitBreaker.state is ConnectionCircuitBreaker.State.HalfOpen
    ) {
        logger.info("[BLE] Circuit breaker: open → halfOpen (cooldown elapsed)")
    }
}

internal fun ConnectionManager.noteCircuitSuccess() {
    val before = circuitBreaker.state
    circuitBreaker.recordSuccess()
    if (before !is ConnectionCircuitBreaker.State.Closed) {
        logger.info("[BLE] Circuit breaker: → closed (connection succeeded)")
    }
}

internal fun ConnectionManager.noteCircuitFailure() {
    val before = circuitBreaker.state
    circuitBreaker.recordFailure()
    when (before) {
        is ConnectionCircuitBreaker.State.Closed ->
            logger.warning("[BLE] Circuit breaker: closed → open (retries exhausted)")
        is ConnectionCircuitBreaker.State.HalfOpen ->
            logger.warning("[BLE] Circuit breaker: halfOpen → open (probe failed)")
        is ConnectionCircuitBreaker.State.Open -> Unit
    }
}

/**
 * Ends the connect-window notification mute. Idempotent per [generation] so an early post-poll
 * clear and the ceremony `finally` cannot double-resume or steal a successor's window.
 */
internal fun ConnectionManager.clearSyncSuppressionIfOwner(
    generation: Long,
    container: ServiceContainer,
    context: String,
) {
    if (syncSuppressionOwner != generation || !canResumeSyncNotifications(container)) return
    syncSuppressionOwner = null
    logger.info(resumingNotificationsLine(context))
    notifier.setSyncNotificationSuppression(false)
}

/**
 * The post-sync tail shared by the initial window and a recovered resync: enable the session's own
 * message drain now that the backlog drain is done, promote, correct the radio clock, and replay any
 * remote-node logins a drop left open.
 *
 * Auto-fetch is enabled here rather than with the rest of the live pipeline because the session's drain
 * pops messages off the radio itself and delivers each only as an event, which `MessagePoller` discards
 * while its own backlog drain holds the gate — two drains running together lose inbound messages.
 */
internal suspend fun ConnectionManager.completeSync(handle: String, container: ServiceContainer, generation: Long) {
    logger.info(syncStartingAutoFetchLine(container.radioId))
    container.liveSync.startAutoMessageFetching()
    reauthenticatePendingRemoteSessions(container, generation)
    // Both awaits above are wide enough for a disconnect or a fresh connect to land inside them, and
    // neither cancels this coroutine, so the promotion is re-fenced rather than taken on trust.
    if (!ownsConnectCeremony(generation, container)) {
        logPromotionSuppressed(generation, container)
        return
    }
    mutableState.value = ConnectionState.Ready(handle)
    logger.info(SYNC_STATE_SYNCED)
    logger.info(connectionSetupCompleteLine(container.radioId))
    syncDeviceTimeIfNeeded()
}

/**
 * Bluetooth reachability for a Device row. [peripheralUuid] is the stable row id; [handle] (the
 * Kable peripheral id / MAC) is stored in `displayName` so multi-device reconnect can recover it
 * — Android cannot reverse [StableIds] back to the transport handle.
 */
internal fun ConnectionManager.bleReachability(rowId: Uuid, handle: String): List<ConnectionMethod> =
    listOf(ConnectionMethod.Bluetooth(peripheralUuid = rowId, displayName = handle))

// The app-level liveness probe. ESP32 ignores TCP keepalives, so a failed getTime() is a dead
// companion: iOS `startWiFiHeartbeat` disconnects and reconnects. A missed reply while other
// mesh traffic keeps the decoder busy would otherwise never trip the inbound-silence watchdog.

@Suppress("TooGenericExceptionCaught")
internal suspend fun ConnectionManager.wifiHeartbeatProbe(fromForeground: Boolean = false) {
    if (shouldPauseWiFiHeartbeat(mutableState.value, channelRetryJob?.isActive == true)) {
        logger.info(WIFI_HEARTBEAT_SKIPPED)
        return
    }
    val liveSession = session ?: return
    try {
        liveSession.getTime()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        if (shouldPauseWiFiHeartbeat(mutableState.value, channelRetryJob?.isActive == true)) {
            logger.info(WIFI_HEARTBEAT_INCONCLUSIVE)
            return
        }
        if (fromForeground) logger.info(WIFI_FOREGROUND_TRANSPORT_DEAD)
        logger.warning(wifiHeartbeatFailedLine(error.message ?: error.toString()))
        val deviceId = currentDeviceId ?: return
        handleLinkDrop(deviceId, LinkDropReason.CONNECTION_LOST, connectGeneration, liveSession)
    }
}

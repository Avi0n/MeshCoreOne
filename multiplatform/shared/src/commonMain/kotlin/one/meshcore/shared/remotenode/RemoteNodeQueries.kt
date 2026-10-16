package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.CommandAuditLogger
import kotlin.time.Duration

/** Requests a repeater/room status frame, applying the binary timeout ceiling. */
suspend fun RemoteNodeService.requestStatus(sessionID: Uuid, timeout: Duration? = null): StatusResponse {
    val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
    val target = auditTarget(session)
    auditLogger.logStatusRequest(target, session.publicKey)
    val response = withBinaryTimeout(timeout) { nodeSession.requestStatus(session.publicKey) }
    auditLogger.logStatusResponse(
        target,
        session.publicKey,
        batteryMv = response.battery,
        uptimeSec = response.uptime,
    )
    return response
}

/** Requests telemetry, applying the binary timeout ceiling. The LPP payload is decoded bridge-side. */
suspend fun RemoteNodeService.requestTelemetry(sessionID: Uuid, timeout: Duration? = null): TelemetryResponse {
    val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
    val target = auditTarget(session)
    auditLogger.logTelemetryRequest(target, session.publicKey)
    val response = withBinaryTimeout(timeout) { nodeSession.requestTelemetry(session.publicKey) }
    auditLogger.logTelemetryResponse(target, session.publicKey, pointCount = response.rawData.size)
    return response
}

/** Requests owner info, applying the binary timeout ceiling. */
suspend fun RemoteNodeService.requestOwnerInfo(sessionID: Uuid, timeout: Duration? = null): OwnerInfoResponse {
    val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
    return withBinaryTimeout(timeout) { nodeSession.requestOwnerInfo(session.publicKey) }
}

internal fun RemoteNodeService.auditTarget(session: RemoteNodeSessionDto): CommandAuditLogger.Target =
    if (session.isRoom) CommandAuditLogger.Target.ROOM else CommandAuditLogger.Target.REPEATER

private suspend fun <T> RemoteNodeService.withBinaryTimeout(timeout: Duration?, request: suspend () -> T): T {
    val effectiveTimeout = timeout ?: RemoteOperationTimeoutPolicy.BINARY_MAXIMUM
    return try {
        val response = withTimeoutOrNull(effectiveTimeout) { Result.success(request()) }
            ?: throw RemoteNodeError.Timeout
        response.getOrThrow()
    } catch (error: MeshError) {
        remoteNodeFailure(error)
    }
}

/**
 * Called when the BLE link is lost. Marks every connected session disconnected, stops its keep-alive,
 * and emits a disconnected event; returns their ids so [handleBLEReconnection] can re-auth them.
 */
suspend fun RemoteNodeService.handleBLEDisconnection(): Set<Uuid> {
    currentCoroutineContext().ensureActive()
    if (connectionRetired.isCompleted) return emptySet()
    return withConnectionOperation {
        captureDisconnectedSessions()
    }
}

private suspend fun RemoteNodeService.captureDisconnectedSessions(): Set<Uuid> {
    currentCoroutineContext().ensureActive()
    @Suppress("TooGenericExceptionCaught")
    suspend fun connectedSessions(): List<RemoteNodeSessionDto> = try {
        store.fetchConnectedSessions()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        logger.error(
            "Failed to fetch connected sessions for BLE disconnection: ${error.message ?: error}",
        )
        emptyList()
    }
    val connected = connectedSessions()
    currentCoroutineContext().ensureActive()
    if (connected.isEmpty()) return emptySet()
    logger.info("BLE disconnection: marking ${connected.size} session(s) disconnected")
    for (session in connected) {
        stopKeepAlive(session.id)
        currentCoroutineContext().ensureActive()
        persistDisconnected(session.id, markSessionLine = true)
        currentCoroutineContext().ensureActive()
        emit(RemoteNodeEvent.SessionStateChanged(session.id, isConnected = false))
    }
    return connected.map { it.id }.toSet()
}

internal suspend fun RemoteNodeService.markDisconnectedWithEvent(sessionID: Uuid) {
    persistDisconnected(sessionID)
    currentCoroutineContext().ensureActive()
    emit(RemoteNodeEvent.SessionStateChanged(sessionID, isConnected = false))
}

package one.meshcore.shared.remotenode

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Periodic keep-alive cadence, matching Swift `RemoteNodeService.defaultKeepAliveInterval`. */
internal val DEFAULT_KEEP_ALIVE_INTERVAL: Duration = 90.seconds

/** Starts periodic keep-alive for a session, with an immediate first tick. */
suspend fun RemoteNodeService.startSessionKeepAlive(sessionID: Uuid, publicKey: ByteArray) {
    stopKeepAlive(sessionID)
    val job = scope.launch {
        var failures = 0
        while (isActive) {
            val tick = performKeepAliveTick(sessionID, publicKey, failures)
            if (!tick.shouldContinue) break
            failures = tick.consecutiveFailures
            delay(DEFAULT_KEEP_ALIVE_INTERVAL)
        }
    }
    mutex.withLock { keepAliveJobs[sessionID] = job }
}

/** Stops keep-alive for a session. */
suspend fun RemoteNodeService.stopSessionKeepAlive(sessionID: Uuid) = stopKeepAlive(sessionID)

/** Sends a single manual keep-alive for a session. */
suspend fun RemoteNodeService.sendKeepAlive(sessionID: Uuid) {
    val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
    sendKeepAliveIfDirectRouted(sessionID, session.publicKey)
}

internal suspend fun RemoteNodeService.sendKeepAliveIfDirectRouted(sessionID: Uuid, publicKey: ByteArray) {
    val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
    val floodRouted = store.contactFloodRouted(session.radioID, publicKey) ?: throw RemoteNodeError.ContactNotFound
    rejectFloodKeepAlive(floodRouted)
    auditLogger.logKeepAlive(auditTarget(session), publicKey)
    try {
        nodeSession.sendKeepAlive(publicKey, session.lastSyncTimestamp)
    } catch (e: MeshError) {
        keepAliveMeshFailure(e)
    }
}

internal suspend fun RemoteNodeService.stopKeepAlive(sessionID: Uuid) {
    mutex.withLock { keepAliveJobs.remove(sessionID) }?.cancel()
}

/** Cancels all keep-alive loops and resumes every parked login so no caller hangs (app termination). */
suspend fun RemoteNodeService.stopAllKeepAlives() {
    val (jobs, prefixes) = mutex.withLock {
        val j = keepAliveJobs.values.toList()
        keepAliveJobs.clear()
        j to pendingLogins.keys.toList()
    }
    jobs.forEach { it.cancel() }
    prefixes.forEach { cancelPendingLogin(it, expected = null) }
}

/** Number of login continuations currently parked. Exposed for teardown tests. */
suspend fun RemoteNodeService.pendingLoginCount(): Int = mutex.withLock { pendingLogins.size }

private fun rejectFloodKeepAlive(floodRouted: Boolean) {
    if (floodRouted) throw RemoteNodeError.FloodRouted
}

private fun keepAliveMeshFailure(error: MeshError): Nothing = throw RemoteNodeError.SessionError(error)

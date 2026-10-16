package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.transport.MeshLinkConnectException
import one.meshcore.shared.transport.deviceHandlePrefix

// Brings the link up and runs the handshake, leaving `link`/`session` installed. start() must be in
// flight when the link reports connected (the bridge handshake resumes on reportConnected), so it is
// launched before the link is driven up, mirroring the proven ordering.
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConnectionManager.openLinkAndHandshake(
    handle: String,
    newLink: MeshLink,
): Pair<SelfInfo, DeviceCapabilities> {
    val startDeferred = installSessionOnLink(handle, newLink)
    try {
        newLink.connect()
    } catch (cancelled: CancellationException) {
        startDeferred.cancel()
        throw cancelled
    } catch (error: Throwable) {
        startDeferred.cancel()
        throw error
    }
    if (currentTransportType == TransportType.BLUETOOTH) {
        logger.info(bleStateConnectedLine(deviceHandlePrefix(handle)))
    }
    return completeHandshake(startDeferred)
}

/**
 * App-start + queryDevice over a GATT that is already up. iOS `rebuildSession`: stop the failed
 * session without disconnecting the transport, re-vend the stream, and handshake again.
 */
internal suspend fun ConnectionManager.handshakeOverExistingLink(
    handle: String,
    existingLink: MeshLink,
    generation: Long,
): Pair<SelfInfo, DeviceCapabilities> {
    logger.info(bleRebuildingSessionLine(deviceHandlePrefix(handle)))
    dropSessionKeepingLink()
    throwIfSuperseded(generation)
    val retiredContext = services == null && session == null
    if (link !== existingLink || !retiredContext) throw CancellationException("session rebuild superseded")
    val startDeferred = installSessionOnLink(handle, existingLink)
    return completeHandshake(startDeferred)
}

internal fun ConnectionManager.installSessionOnLink(handle: String, meshLink: MeshLink): Deferred<SelfInfo> {
    val newSession = sessionFactory(meshLink)
    link = meshLink
    session = newSession
    meshLink.setListener(ConnectionLinkAdapter(this, handle, newSession, connectGeneration))
    return scope.async { newSession.start() }
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun ConnectionManager.completeHandshake(
    startDeferred: Deferred<SelfInfo>,
): Pair<SelfInfo, DeviceCapabilities> {
    val liveSession = session ?: run {
        startDeferred.cancel()
        throw MeshLinkConnectException("session cleared during handshake")
    }
    liveSession.reportConnected()
    return try {
        val selfInfo = awaitHandshakeStep("session.start") { startDeferred.await() }
        val capabilities = awaitHandshakeStep("queryDevice") { liveSession.queryDevice() }
        selfInfo to capabilities
    } catch (cancelled: CancellationException) {
        rethrowHandshakeCancellation(startDeferred, cancelled)
    } catch (error: Throwable) {
        rethrowHandshakeFailure(startDeferred, error)
    }
}

private suspend fun ConnectionManager.rethrowHandshakeCancellation(
    startDeferred: Deferred<SelfInfo>,
    error: CancellationException,
): Nothing {
    startDeferred.cancel()
    val liveLink = link
    if (liveLink != null && !liveLink.isConnected) {
        disconnectDeadLink(logger::warning, error) { liveLink.disconnect() }
    }
    throw error
}

private fun ConnectionManager.rethrowHandshakeFailure(startDeferred: Deferred<SelfInfo>, error: Throwable): Nothing {
    startDeferred.cancel()
    throw if (currentTransportType == TransportType.BLUETOOTH && link?.isConnected == true) {
        SessionRebuildException(error)
    } else {
        error
    }
}

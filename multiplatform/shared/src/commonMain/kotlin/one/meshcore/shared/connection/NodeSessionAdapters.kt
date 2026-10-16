package one.meshcore.shared.connection

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodeQuerySession
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.remotenode.RemoteNodeStore
import one.meshcore.shared.remotenode.sendCLICommand
import one.meshcore.shared.remotenode.sendRawCLICommand
import one.meshcore.shared.remotenode.toMeshError
import one.meshcore.shared.room.RoomAdminSession
import one.meshcore.shared.room.RoomMessageRetry
import one.meshcore.shared.room.RoomServerSession
import one.meshcore.shared.runExceptionFallback
import kotlin.time.Duration

/** Public-key bytes carried on a login/CLI/status send. */
private const val PUBLIC_KEY_SIZE: Int = 32

/** The addressing prefix a message send targets, mirroring the Swift `destination.prefix(6)`. */
private const val SEND_PREFIX_LENGTH: Int = 6

private const val MILLISECONDS_PER_SECOND: Double = 1000.0

/**
 * Bridges the live [MeshSession] to the [NodeSession] seam the remote-node services depend on. The command
 * ops delegate straight to the session (a bridge `MeshBridgeException` is re-thrown as the typed [MeshError]
 * the services classify on); [events] re-exposes the session's hot stream, so [RemoteNodeService]'s login
 * collector and [NodeCliCoordinator]'s CLI collector fold the same pushed events the chat poller sees — one
 * multicast flow, no second poller. `sendCommand` stamps the wall clock the firmware echoes on the reply.
 *
 * This seam addresses repeaters, so a status request carries the repeater node type and the bridge reads
 * the repeater status layout. Rooms go through [RoomAdminSession].
 */
class NodeSessionAdapter(private val session: MeshSession, private val now: () -> Long) :
    NodeSession,
    NodeQuerySession by NodeQueryBridge(session) {
    override val events: SharedFlow<MeshEvent> = session.events

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo =
        mapNodeError { session.sendLogin(destination, password) }

    override suspend fun sendLogout(destination: ByteArray) = mapNodeError { session.sendLogout(destination) }

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo =
        mapNodeError { session.sendKeepAlive(publicKey, syncSince) }

    override suspend fun addContact(contact: MeshContact) = mapNodeError { session.addContact(contact) }

    override suspend fun resetPath(publicKey: ByteArray) = mapNodeError { session.resetPath(publicKey) }

    override suspend fun sendCommand(destination: ByteArray, command: String): MessageSentInfo =
        mapNodeError { session.sendCommand(destination, command, now()) }

    override suspend fun getMessage(): GetMessageResult = mapNodeError { session.getMessage() }
}

/** Status, telemetry, owner, and neighbour reads for [NodeSessionAdapter]. */
private class NodeQueryBridge(private val session: MeshSession) : NodeQuerySession {
    override suspend fun requestStatus(publicKey: ByteArray): StatusResponse =
        mapNodeError { session.requestStatus(publicKey, ContactType.REPEATER) }

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse =
        mapNodeError { session.requestTelemetry(publicKey) }

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse =
        mapNodeError { session.requestOwnerInfo(publicKey) }

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse =
        mapNodeError { session.requestNeighbours(publicKey, count, offset, orderBy, pubkeyPrefixLength) }
}

/**
 * Bridges the live [MeshSession] to the [RoomServerSession] seam. [sendMessageWithRetry] ports the Swift
 * `MeshCoreSession.sendMessageWithRetry` retry loop verbatim: send to the 6-byte prefix, wait for the
 * end-to-end ACK, and after [floodAfter] failures reset the path so the retry floods. A `null` return means
 * every attempt went unacknowledged (the packet likely reached the server — a `.sent`, not a failure).
 * `requestHistorySync` requests a status frame, which is what triggers the room to push its backlog.
 */
class RoomServerSessionAdapter(private val session: MeshSession) : RoomServerSession {
    override suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        retry: RoomMessageRetry,
    ): MessageSentInfo? {
        require(destination.size >= PUBLIC_KEY_SIZE) {
            "Full $PUBLIC_KEY_SIZE-byte public key required for retry with path reset"
        }
        val prefix = destination.copyOf(SEND_PREFIX_LENGTH)
        var attempts = 0
        var floodAttempts = 0
        var isFloodMode = false

        while (attempts < retry.maxAttempts && (!isFloodMode || floodAttempts < retry.maxFloodAttempts)) {
            if (attempts == retry.floodAfter && !isFloodMode) {
                // A failed path reset leaves the loop on direct retries. Cancellation still stops the send.
                if (resetPathForFlood(session, destination)) isFloodMode = true
            }

            val sentInfo = mapNodeError { session.sendMessage(prefix, text, timestamp, attempts) }
            val ackTimeout =
                sentInfo.suggestedTimeoutMs / MILLISECONDS_PER_SECOND * ChatConstants.ACK_TIMEOUT_MULTIPLIER
            if (waitForAck(session, sentInfo.expectedAck, ackTimeout)) return sentInfo

            attempts += 1
            if (isFloodMode) floodAttempts += 1
        }
        return null
    }

    override suspend fun sendPathDiscovery(destination: ByteArray) {
        mapNodeError { session.sendPathDiscovery(destination) }
    }

    override suspend fun requestHistorySync(destination: ByteArray) {
        mapNodeError { session.requestStatus(destination, ContactType.ROOM) }
    }
}

/**
 * Bridges the live [MeshSession] and the shared [NodeCliCoordinator] to the [RoomAdminSession] seam. Status
 * and telemetry apply the caller's request-response ceiling directly over the session; the CLI ops delegate
 * to the one [cliCoordinator] (resolving the room's session id by key prefix) so a room's admin commands
 * share the single CLI response consumer rather than opening a competing one.
 *
 * Every target here is a room, so a status request carries the room node type and the frame arrives already
 * decoded with the room-server layout — its posted and post-push counters occupy the field a repeater uses
 * for RX airtime, and nothing above the bridge re-reads them.
 */
class RoomAdminSessionAdapter(
    private val session: MeshSession,
    private val cliCoordinator: NodeCliCoordinator,
    private val store: RemoteNodeStore,
) : RoomAdminSession {
    override suspend fun requestStatus(publicKey: ByteArray, timeout: Duration): StatusResponse =
        withNodeTimeout(timeout) { session.requestStatus(publicKey, ContactType.ROOM) }

    override suspend fun requestTelemetry(publicKey: ByteArray, timeout: Duration): TelemetryResponse =
        withNodeTimeout(timeout) { session.requestTelemetry(publicKey) }

    override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
        cliCoordinator.sendCLICommand(sessionId(publicKey), command, timeout)

    override suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
        cliCoordinator.sendRawCLICommand(sessionId(publicKey), command, timeout)

    private suspend fun sessionId(publicKey: ByteArray) =
        (store.fetchSessionByPublicKey(publicKey) ?: throw MeshError.NotConnected).id
}

/**
 * Resets the room path before a flood retry. A thrown failure stays on direct retries.
 * Cancellation still stops the send, and an error still propagates.
 */
private suspend fun resetPathForFlood(session: MeshSession, destination: ByteArray): Boolean =
    runExceptionFallback({ false }) {
        mapNodeError { session.resetPath(destination) }
        true
    }

/** Folds the pushed event stream for the matching end-to-end ACK, returning false on timeout. */
private suspend fun waitForAck(session: MeshSession, code: ByteArray, timeoutSeconds: Double): Boolean =
    withTimeoutOrNull((timeoutSeconds * MILLISECONDS_PER_SECOND).toLong()) {
        session.events
            .filterIsInstance<MeshEvent.Acknowledgement>()
            .first { it.code.contentEquals(code) }
        true
    } ?: false

// A bridge failure is intentionally re-thrown as the typed MeshError the remote-node services classify on;
// the bridge exception is the source of that signal, not lost.
private suspend inline fun <T> mapNodeError(block: suspend () -> T): T = try {
    block()
} catch (e: MeshBridgeException) {
    throw meshErrorFromBridge(e)
}

private fun meshErrorFromBridge(error: MeshBridgeException): MeshError = error.toMeshError()

private suspend fun <T> withNodeTimeout(timeout: Duration, request: suspend () -> T): T = mapNodeError {
    val response = withTimeoutOrNull(timeout) { Result.success(request()) } ?: throw MeshError.Timeout
    response.getOrThrow()
}

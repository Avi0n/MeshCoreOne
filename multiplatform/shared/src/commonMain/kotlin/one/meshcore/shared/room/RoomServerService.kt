package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import one.meshcore.shared.chat.ackCodeLeUInt32
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeEvent
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.runReportingThrowable
import one.meshcore.shared.settings.PersistentLogger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** The author-key prefix carried on the wire and used for self-comparison. */
internal const val AUTHOR_KEY_PREFIX_BYTES: Int = 4

/** History path-discovery bounds, mirroring the Swift `discoverPathAndWait`. */
private val PATH_DISCOVERY_TIMEOUT: Duration = 10.seconds
private val PATH_DISCOVERY_POLL: Duration = 500.milliseconds

private const val EVENT_BUFFER: Int = 32

/**
 * Room-server interactions, mirroring the subset of the Swift `RoomServerService` this slice ports: join /
 * reconnect / leave (over the shared [RemoteNodeService] login path), optimistic posting with a background
 * retry that resolves to delivered / sent / failed, incoming-message ingest with deduplication and
 * connection recovery, best-effort history sync, and the message/session reads.
 *
 * One instance per live connection (rebuilt on reconnect, torn down on disconnect). Mutable state
 * (`selfPublicKeyPrefix`, `inFlightRetries`) is confined behind [mutex], mirroring the Swift actor;
 * background sends run on the injected [scope]. Events are multicast on [events]; a consumer re-subscribes
 * per connection.
 */
@OptIn(ExperimentalTime::class)
class RoomServerService(
    internal val remoteNodeService: RemoteNodeService,
    internal val store: RoomStore,
    private val roomSession: RoomServerSession,
    internal val passwordStore: NodePasswordStore,
    internal val scope: CoroutineScope,
    edges: RoomServerEdges = RoomServerEdges(),
) {
    internal val config = edges.config
    internal val newId: () -> Uuid = edges.newId ?: Uuid::random
    internal val now: () -> Long = edges.now ?: { Clock.System.now().epochSeconds }
    internal val auditLogger = edges.auditLogger
    private val logger = edges.logger ?: PersistentLogger(scope, category = "RoomServerService")

    internal val mutex = Mutex()

    /** First [AUTHOR_KEY_PREFIX_BYTES] bytes of this radio's public key, for author self-comparison. */
    internal var selfPublicKeyPrefix: ByteArray? = null

    /** Message ids with a retry in flight, so a second retry for the same id is rejected. */
    internal val inFlightRetries = mutableSetOf<Uuid>()

    private val _events = MutableSharedFlow<RoomServerEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Multicast stream of room-message status changes and connection-recovery notifications. */
    val events: SharedFlow<RoomServerEvent> = _events.asSharedFlow()

    /**
     * Session connection-state changes from the shared node service, so a room surface refreshes its
     * banners and can-post state on login, logout, and a keep-alive-driven disconnect.
     */
    val sessionEvents: SharedFlow<RemoteNodeEvent> get() = remoteNodeService.events

    /**
     * Runs one send-with-retry and folds its outcome into the message's status and the room activity. A
     * successful post persists ACK metadata; an exhausted-but-unacked post is `sent`; a thrown send is
     * `failed` and leaves activity untouched. Status persistence itself is best-effort so a post-send store
     * error cannot flip a real success back to failed.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun runSend(
        messageID: Uuid,
        sessionID: Uuid,
        publicKey: ByteArray,
        text: String,
        timestamp: Long,
    ) {
        // A failed send marks the row failed and logs. Cancellation leaves the row pending.
        val sentInfo = try {
            roomSession.sendMessageWithRetry(
                destination = publicKey,
                text = text,
                timestamp = timestamp,
                retry = RoomMessageRetry(
                    maxAttempts = config.maxAttempts,
                    floodAfter = config.floodAfter,
                    maxFloodAttempts = config.maxFloodAttempts,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // A failed status write is logged, and the failed event still fires. Cancellation leaves the row pending.
            runReportingThrowable(
                onFailure = { dbError ->
                    logger.error(
                        "Failed to update message status after send error: ${dbError.message ?: dbError}",
                    )
                },
            ) {
                store.updateRoomMessageStatus(messageID, MessageStatus.FAILED, null, null)
            }
            emit(RoomServerEvent.StatusUpdated(messageID, MessageStatus.FAILED))
            logger.warning("Room message send failed: ${error.message ?: error.toString()}")
            return
        }

        val status = if (sentInfo != null) MessageStatus.DELIVERED else MessageStatus.SENT
        recordResolvedRoomSend(
            messageID = messageID,
            status = status,
            ackCode = sentInfo?.expectedAck?.ackCodeLeUInt32(),
            roundTripTime = sentInfo?.suggestedTimeoutMs,
        )
        emit(RoomServerEvent.StatusUpdated(messageID, status))
        runReportingThrowable(
            onFailure = { error -> logger.warning("Failed to update room activity: ${error.message ?: error}") },
        ) {
            store.updateRoomActivity(sessionID)
        }
    }

    /**
     * Persists a resolved room send. A failed write is logged and the caller still reports [status].
     * Cancellation leaves the row unchanged and skips that report.
     */
    private suspend fun recordResolvedRoomSend(
        messageID: Uuid,
        status: MessageStatus,
        ackCode: Long?,
        roundTripTime: Long?,
    ) {
        runReportingThrowable(
            onFailure = { error ->
                val line = if (status == MessageStatus.DELIVERED) {
                    "Failed to update message status after successful send: ${error.message ?: error}"
                } else {
                    "Failed to update message status to sent: ${error.message ?: error}"
                }
                logger.error(line)
            },
        ) {
            store.updateRoomMessageStatus(
                id = messageID,
                status = status,
                ackCode = ackCode,
                roundTripTime = roundTripTime,
            )
        }
    }

    /**
     * Best-effort history sync. Tries the advertised path first when the contact is directly routed; on
     * failure (or when flood-routed) triggers path discovery and retries once a direct route appears. Never
     * throws: a join succeeds regardless, and missed messages still arrive via the normal push flow.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun syncHistoryIfPossible(sessionID: Uuid) {
        suspend fun syncedOnAdvert(name: String, publicKey: ByteArray): Boolean {
            logger.info(tryingAdvertPathForRoomLine(name))
            // A failed advert path is logged and the caller tries discovery. Cancellation skips that.
            return runReportingThrowable(
                onFailure = { error ->
                    logger.info(advertPathFailedLine(name, error.message ?: error.toString()))
                    false
                },
            ) {
                roomSession.requestHistorySync(publicKey)
                logger.info(requestedHistorySyncForRoomLine(name))
                logger.info(HISTORY_SYNC_SUCCEEDED_USING_ADVERT_PATH)
                true
            }
        }

        // A failed history sync is logged and the join still succeeds. Cancellation still propagates.
        try {
            val session = store.fetchSession(sessionID) ?: return
            val floodRouted = store.contactFloodRouted(session.radioID, session.publicKey)
                ?: return
            val advertSucceeded = !floodRouted && syncedOnAdvert(session.name, session.publicKey)
            if (!advertSucceeded) {
                if (floodRouted) logger.info(roomIsFloodRoutedLine(session.name))
                if (discoverPathAndWait(session.radioID, session.publicKey)) {
                    roomSession.requestHistorySync(session.publicKey)
                    logger.info(requestedHistorySyncForRoomLine(session.name))
                    logger.info(HISTORY_SYNC_SUCCEEDED_AFTER_PATH_DISCOVERY)
                } else {
                    logger.info(couldNotEstablishDirectRouteLine(session.name))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.warning("Failed to sync history for session $sessionID: ${error.message ?: error}")
        }
    }

    private suspend fun discoverPathAndWait(radioID: Uuid, publicKey: ByteArray): Boolean {
        if (store.contactFloodRouted(radioID, publicKey) == false) return true
        suspend fun sent(): Boolean = runReportingThrowable(
            // A failed path-discovery send is logged and discovery stops. Cancellation still propagates.
            onFailure = { error ->
                logger.warning("Path discovery send failed: ${error.message ?: error}")
                false
            },
        ) {
            roomSession.sendPathDiscovery(publicKey)
            true
        }
        return if (sent()) {
            pollUntilDirect(PATH_DISCOVERY_TIMEOUT, PATH_DISCOVERY_POLL) {
                store.contactFloodRouted(radioID, publicKey) == false
            }
        } else {
            false
        }
    }

    internal fun emit(event: RoomServerEvent) {
        _events.tryEmit(event)
    }
}

private suspend fun pollUntilDirect(timeout: Duration, interval: Duration, isDirect: suspend () -> Boolean): Boolean {
    val start = TimeSource.Monotonic.markNow()
    while (start.elapsedNow() < timeout) {
        delay(interval)
        if (isDirect()) return true
    }
    return false
}

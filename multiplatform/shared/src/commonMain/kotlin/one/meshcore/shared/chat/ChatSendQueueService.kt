package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * Owns the DM and channel send queues for one connection: durable enqueue, restart-survival hydrate, the
 * park-and-retry drain control flow, the three-state [PendingSendDto.attemptCount] preserve-timestamp
 * logic, and the channel NOT_FOUND pool-exhaustion-vs-deleted disambiguation. Constructed once per
 * connection and torn down on disconnect. The Kotlin port of the Swift `ChatSendQueueService`.
 *
 * The drain suspends on the [triggers] wake signal; [observeConnectionState] fires it each time the
 * connection enters `.ready`, keeping hydrated sends parked through the initial-sync window. Rows are
 * never deleted while parked; [triggers] is cleared only after a successful send.
 */
class ChatSendQueueService(
    var radioID: Uuid,
    collaborators: ChatSendQueueCollaborators,
    edges: ChatSendQueueEdges = ChatSendQueueEdges(),
) {
    internal val store = collaborators.store
    internal val messageService = collaborators.messageService
    internal val channelResolver = collaborators.channelResolver
    internal val scope = collaborators.scope
    internal val now = collaborators.now
    internal val newPendingSendId = collaborators.newPendingSendId
    internal val reactionIndexer = edges.reactionIndexer
    internal val config = edges.config
    internal val logger = edges.logger ?: PersistentLogger(scope, category = LOGGER_CATEGORY)

    internal val triggers = BleTransportOpenedSignal()
    internal val channelFetchFailures = FailureCounter()

    internal val dmQueue = SendQueue<DirectMessageEnvelope>(
        scope = scope,
        send = { envelope -> drainDirectMessage(envelope) },
        onError = { _, envelope -> persistDrainState { store.deletePendingSendsForMessage(envelope.messageID) } },
        onDrain = { lastError ->
            if (lastError != null) logger.error(dmQueueDrainedErrorLine(lastError))
        },
    )
    internal val channelQueue = SendQueue<ChannelMessageEnvelope>(
        scope = scope,
        send = { envelope -> drainChannelMessage(envelope) },
        onError = { _, envelope -> persistDrainState { store.deletePendingSendsForMessage(envelope.messageID) } },
        onDrain = { lastError ->
            if (lastError != null) logger.error(channelQueueDrainedErrorLine(lastError))
        },
    )

    internal val retirement = CompletableDeferred<Unit>()
    internal val admissionMutex = Mutex()
    val isRetired: Boolean get() = retirement.isCompleted
    val retirementState: Flow<Boolean> = flow {
        val alreadyRetired = isRetired
        emit(alreadyRetired)
        if (!alreadyRetired) {
            retirement.await()
            emit(true)
        }
    }

    internal val hydrateMutex = Mutex()
    internal var hasHydrated = false
    internal var connectionStateJob: Job? = null

    internal companion object {
        const val LOGGER_CATEGORY: String = "ChatSendQueueService"
        const val KIND_DM: String = "DM"
        const val KIND_CHANNEL: String = "channel"
    }
}

/**
 * Consecutive `channelExists` throw counter keyed by message id, so one envelope's persistent fetch
 * failures do not terminal-fail a different envelope on its first throw. Mirrors the Swift `FailureCounter`
 * actor. Cleaned up on success and terminal-fail so the map does not grow unboundedly.
 */
internal class FailureCounter {
    private val mutex = Mutex()
    private val counts = mutableMapOf<Uuid, Int>()

    suspend fun increment(messageID: Uuid): Int = mutex.withLock {
        val next = (counts[messageID] ?: 0) + 1
        counts[messageID] = next
        next
    }

    suspend fun reset(messageID: Uuid) {
        mutex.withLock { counts.remove(messageID) }
    }
}

internal fun dmDrainTerminalLine(messageID: Uuid, error: Throwable): String =
    "DM drain terminal messageID=$messageID error=$error"

internal fun dmDrainTransientLine(messageID: Uuid, error: Throwable): String =
    "DM drain transient messageID=$messageID error=$error"

internal fun dmQueueDrainedErrorLine(error: Throwable): String = "DM queue drained with error: $error"

internal fun channelDrainTerminalLine(messageID: Uuid, error: Throwable): String =
    "channel drain terminal messageID=$messageID error=$error"

internal fun channelDrainTransientLine(messageID: Uuid, error: Throwable): String =
    "channel drain transient messageID=$messageID error=$error"

internal fun channelQueueDrainedErrorLine(error: Throwable): String = "Channel queue drained with error: $error"

internal fun channelDrainNotFoundBelowThresholdLine(messageID: Uuid, postBumpCount: Int): String =
    "channel drain NOT_FOUND below disambiguate threshold messageID=$messageID " +
        "postBumpCount=$postBumpCount; parking envelope"

internal fun channelDrainFetchChannelPersistentLine(messageID: Uuid, failures: Int): String =
    "Channel drain: fetchChannel persistently failing ($failures consecutive) for messageID=$messageID; " +
        "marking terminal"

internal fun channelDrainFetchChannelFailedLine(error: Throwable, failures: Int, cap: Int): String =
    "channel drain NOT_FOUND followup fetchChannel failed: $error; parking envelope ($failures/$cap)"

internal fun channelDrainNotFoundConfirmedLine(messageID: Uuid): String =
    "Channel drain: NOT_FOUND confirmed by fetchChannel for messageID=$messageID; " +
        "treating as terminal (channel deleted)"

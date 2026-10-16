package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.model.Uuid

/** A reaction awaiting its channel target message; drained when a matching message is later indexed. */
data class PendingReaction(
    val parsed: ParsedReaction,
    val channelIndex: Int,
    val senderNodeName: String,
    val rawText: String,
    val radioID: Uuid,
    val receivedAt: Double,
)

/** A reaction awaiting its DM target message. */
data class PendingDmReaction(
    val parsed: ParsedDmReaction,
    val contactID: Uuid,
    val senderName: String,
    val rawText: String,
    val radioID: Uuid,
    val receivedAt: Double,
)

/** The message whose summary changed after a reaction persisted, and its rebuilt summary string. */
data class ReactionPersistResult(val messageID: Uuid, val summary: String)

/** The outcome of an outgoing [ReactionService.react] call. */
sealed interface ReactionSendResult {
    /** Sent and optimistically persisted; [summary] is the rebuilt cache string (null if persist failed). */
    data class Sent(val summary: String?) : ReactionSendResult

    /** The local node already reacted with this emoji (no toggle-off), so nothing was sent. */
    data object AlreadyReacted : ReactionSendResult

    /** An identical (message, emoji) send is already in flight from a rapid double-tap. */
    data object InFlight : ReactionSendResult

    /** The target could not be addressed (channel message with no resolvable sender, or missing contact). */
    data object Skipped : ReactionSendResult

    /** The send failed; [error] is the underlying send error for the caller to surface. */
    data class Failed(val error: Throwable) : ReactionSendResult
}

/** iOS `ChatViewModel.sendReaction` fallback when the connected device has no node name yet. */
const val FALLBACK_REACTION_NODE_NAME = "Me"

/**
 * The sender name stamped on an outgoing reaction. iOS uses `connectedDevice?.nodeName ?? "Me"` so a
 * reaction still addresses when the device row has not yet filled the name; a blank name would skip
 * the send entirely.
 */
fun reactionSenderName(localNodeName: String?): String =
    localNodeName?.takeIf { it.isNotEmpty() } ?: FALLBACK_REACTION_NODE_NAME

/**
 * The reaction matching/persist/send core: an LRU cache and session-lifetime pending queues for inbound
 * matching, the optimistic persist that rebuilds a message's summary, and the outgoing [react] path that
 * dedups, sends through an injected [ReactionSender], and persists optimistically. Port of the Swift
 * `ReactionService` actor plus the `ChatViewModel` reaction send; a mutex provides the actor's serialized
 * access. [newReactionId] and [now] are injected so the service mints no platform state itself.
 */
class ReactionService private constructor(private val parts: ReactionServiceParts) :
    ReactionIndexing by parts.indexing,
    ReactionPersistence by parts.persistence,
    ReactionSending by parts.sending {
    /**
     * Emits after every reaction persist that rewrote a message's summary, whoever caused it — an inbound
     * reaction, a drained pending one, or the local node's own send. An open thread folds this into a row
     * reload so the badge appears while the conversation is on screen instead of only after a teardown and
     * refetch. Mirrors the Swift `.reactionReceived` data event.
     */
    val summaryUpdates: SharedFlow<ReactionPersistResult> = parts.summaryUpdates

    constructor(
        cache: ReactionMessageCache = ReactionMessageCache(),
        newReactionId: () -> Uuid,
        now: () -> Double,
    ) : this(ReactionServiceParts(cache, newReactionId, now))
}

private class ReactionServiceParts(cache: ReactionMessageCache, newReactionId: () -> Uuid, now: () -> Double) {
    private val state = ReactionServiceState(now)
    private val persister = ReactionPersister()
    val summaryUpdates: SharedFlow<ReactionPersistResult> = persister.summaryUpdates
    val indexing = ReactionIndexer(cache, state)
    val persistence: ReactionPersistence = persister
    val sending = ReactionDispatch(state, newReactionId, persister)
}

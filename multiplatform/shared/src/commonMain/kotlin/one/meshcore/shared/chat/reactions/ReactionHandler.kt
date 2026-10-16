package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.ChannelReactionWire
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ReactionHook
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.model.Uuid

internal fun reactionIndexScopeFor(conversation: Conversation, localNodeName: String?): ReactionIndexScope? =
    when (conversation) {
        is Conversation.Direct -> ReactionIndexScope.Direct(conversation.contact)
        is Conversation.Channel -> ReactionIndexScope.Channel(conversation.channel, localNodeName)
        is Conversation.Room -> null
    }

/** Conversation keying for [ReactionHandler.indexMessages]: channels persist a slot, DMs a contact. */
sealed interface ReactionIndexScope {
    /** A channel thread; [localNodeName] resolves an outgoing message's sender, or null when unknown. */
    data class Channel(val channel: ChannelDto, val localNodeName: String?) : ReactionIndexScope

    /** A DM thread scoped to [contact]. */
    data class Direct(val contact: ContactDto) : ReactionIndexScope
}

/**
 * The reaction ingest seam ([ReactionHook]) plus the thread-load indexer. On receive it tries the four
 * inbound formats in order (meshcore-open v3, meshcore-open v1, PocketMesh) and, on a match, consumes the
 * message so ingest writes no chat row. On save/thread-load it indexes messages so a later or already-queued
 * reaction finds its target, persisting drained pendings. Port of the Swift `SyncCoordinator`
 * `+ReactionHandlers` and `ChatViewModel+ReactionIndexing`. [newReactionId] and [now] mint the persisted
 * row's id and receive time; [now] also anchors the DM/live-receive timestamp window.
 */
class ReactionHandler(
    internal val reactionService: ReactionService,
    internal val store: ReactionStore,
    internal val sink: ReactionSummarySink,
    internal val newReactionId: () -> Uuid,
    internal val now: () -> Double,
    edges: ReactionHandlerEdges,
) : ReactionHook {
    internal val notifier = edges.notifier
    internal val localNodeName = edges.localNodeName
    internal val unknownSenderLabel = edges.unknownSenderLabel

    override suspend fun handleDirectReaction(text: String, contact: ResolvedContact, radioID: Uuid): Boolean {
        if (handledOpenDirect(text, contact, radioID)) return true
        val parsed = ReactionParser.parseDM(text) ?: return false
        return persistParsedDirect(parsed, text, contact, radioID)
    }

    override suspend fun handleChannelReaction(wire: ChannelReactionWire): Boolean {
        if (handledOpenChannel(wire)) return true
        return persistPocketMeshChannel(wire)
    }

    override suspend fun indexDirectMessage(messageID: Uuid, contactID: Uuid, text: String, timestamp: Long) {
        val matches = reactionService.indexDmMessage(messageID, contactID, text, timestamp)
        for (pending in matches) persistDirectPending(messageID, pending, contactID, pending.radioID)
    }

    override suspend fun indexChannelMessage(
        messageID: Uuid,
        channelIndex: Int,
        senderNodeName: String,
        text: String,
        timestamp: Long,
    ) {
        val matches = reactionService.indexMessage(messageID, channelIndex, senderNodeName, text, timestamp)
        for (pending in matches) persistChannelPending(messageID, pending, pending.radioID)
    }

    internal fun nowSeconds(): Long = now().toLong()

    internal fun window(anchor: Long): LongRange {
        val boundedAnchor = anchor.coerceIn(0, ReactionConstants.MAX_WIRE_TIMESTAMP)
        val radius = ReactionConstants.REACTION_TIMESTAMP_WINDOW_SECONDS
        val start = (boundedAnchor - radius).coerceAtLeast(0)
        val end = (boundedAnchor + radius).coerceAtMost(ReactionConstants.MAX_WIRE_TIMESTAMP)
        return start..end
    }
}

/** Fields that identify a channel reaction row before id and receive time are minted. */
internal data class ChannelReactionRow(
    val messageID: Uuid,
    val emoji: String,
    val senderName: String,
    val messageHash: String,
    val rawText: String,
    val channelIndex: Int,
    val radioID: Uuid,
)

/** Fields that identify a direct reaction row before id and receive time are minted. */
internal data class DirectReactionRow(
    val messageID: Uuid,
    val emoji: String,
    val senderName: String,
    val messageHash: String,
    val rawText: String,
    val contactID: Uuid,
    val radioID: Uuid,
)

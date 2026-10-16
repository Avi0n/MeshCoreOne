package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** The radio, store, and clocks every ingest run reads. */
data class MessageIngestCollaborators(
    val radioID: Uuid,
    val store: IngestStore,
    val blockedSenders: BlockedSenders,
    val broadcaster: IncomingMessageBroadcaster,
    val selfNodeName: suspend () -> String,
    val now: () -> Double,
    val newMessageId: () -> Uuid,
)

/**
 * Optional ingest seams. Defaults leave mention detection basic and reactions, notifications, and path
 * hooks as no-ops.
 */
data class MessageIngestEdges(
    val selfMentionDetector: SelfMentionDetector = BasicSelfMentionDetector,
    val reactionHook: ReactionHook = ReactionHook.NoOp,
    val notifier: IngestNotifier = IngestNotifier.NoOp,
    val activeConversation: ActiveConversation = ActiveConversation.None,
    val logger: PersistentLogger? = null,
    val onDuplicateChannelPath: suspend (MessageDto) -> Unit = {},
    val onIncomingChannelSaved: suspend (MessageDto) -> Unit = {},
)

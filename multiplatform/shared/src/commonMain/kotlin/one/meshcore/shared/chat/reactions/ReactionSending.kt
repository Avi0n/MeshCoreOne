package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.isOutgoing
import one.meshcore.shared.chat.reactionTimestamp
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable
import one.meshcore.shared.runThrowableResult

/** Builds outgoing reaction wire text and sends the local node's emoji. */
interface ReactionSending {
    fun buildReactionText(emoji: String, targetSender: String, targetText: String, targetTimestamp: Long): String

    fun buildDmReactionText(emoji: String, targetText: String, targetTimestamp: Long): String

    /**
     * Sends the local node's [emoji] reaction to [message]. Blocks a rapid double-tap (in-flight key), skips
     * when the node already reacted with [emoji] (no toggle-off), routes by channel vs DM, sends the wire
     * text, then optimistically persists and returns the rebuilt summary. Mirrors the Swift `sendReaction`.
     */
    suspend fun react(
        emoji: String,
        message: MessageDto,
        localNodeName: String,
        store: ReactionStore,
        sender: ReactionSender,
    ): ReactionSendResult
}

internal class ReactionDispatch(
    private val state: ReactionServiceState,
    private val newReactionId: () -> Uuid,
    private val persistence: ReactionPersistence,
) : ReactionSending {
    override fun buildReactionText(
        emoji: String,
        targetSender: String,
        targetText: String,
        targetTimestamp: Long,
    ): String = ReactionParser.buildReactionText(emoji, targetSender, targetText, targetTimestamp)

    override fun buildDmReactionText(emoji: String, targetText: String, targetTimestamp: Long): String =
        ReactionParser.buildDmReactionText(emoji, targetText, targetTimestamp)

    override suspend fun react(
        emoji: String,
        message: MessageDto,
        localNodeName: String,
        store: ReactionStore,
        sender: ReactionSender,
    ): ReactionSendResult {
        val key = "${message.id.value}-$emoji"
        suspend fun admitted(): Boolean = state.mutex.withLock { state.inFlight.add(key) }
        if (!admitted()) return ReactionSendResult.InFlight
        try {
            return dispatchedReaction(ReactionSendRequest(emoji, message, localNodeName, store, sender))
        } finally {
            state.mutex.withLock { state.inFlight.remove(key) }
        }
    }

    private suspend fun dispatchedReaction(request: ReactionSendRequest): ReactionSendResult {
        val message = request.message
        val already = runThrowableResult({ false }) {
            request.store.reactionExists(message.id, request.localNodeName, request.emoji)
        }
        if (already) return ReactionSendResult.AlreadyReacted
        val channelIndex = message.channelIndex
        val contactID = message.contactID
        return when {
            channelIndex != null -> sendChannelReaction(request, channelIndex)
            contactID != null -> sendDirectReaction(request, contactID)
            else -> ReactionSendResult.Skipped
        }
    }

    private suspend fun sendChannelReaction(request: ReactionSendRequest, channelIndex: Int): ReactionSendResult {
        val message = request.message
        val targetSender = if (message.isOutgoing) {
            request.localNodeName
        } else {
            message.senderNodeName ?: return ReactionSendResult.Skipped
        }
        val text = buildReactionText(request.emoji, targetSender, message.text, message.reactionTimestamp)
        return runReportingThrowable(
            onFailure = { error -> ReactionSendResult.Failed(error) },
        ) {
            request.sender.sendChannelReaction(text, channelIndex, message.radioID)
            val reaction = optimisticReaction(request, text, channelIndex = channelIndex)
            ReactionSendResult.Sent(persistence.persistReactionAndUpdateSummary(reaction, request.store)?.summary)
        }
    }

    private suspend fun sendDirectReaction(request: ReactionSendRequest, contactID: Uuid): ReactionSendResult {
        val contact = runThrowableResult({ null }) {
            request.store.fetchContact(contactID)
        } ?: return ReactionSendResult.Skipped
        val message = request.message
        val text = buildDmReactionText(request.emoji, message.text, message.reactionTimestamp)
        return runReportingThrowable(
            onFailure = { error -> ReactionSendResult.Failed(error) },
        ) {
            request.sender.sendDirectReaction(text, contact)
            val reaction = optimisticReaction(request, text, contactID = contactID)
            ReactionSendResult.Sent(persistence.persistReactionAndUpdateSummary(reaction, request.store)?.summary)
        }
    }

    private fun optimisticReaction(
        request: ReactionSendRequest,
        rawText: String,
        channelIndex: Int? = null,
        contactID: Uuid? = null,
    ): ReactionDto {
        val message = request.message
        return ReactionDto(
            id = newReactionId(),
            messageID = message.id,
            emoji = request.emoji,
            senderName = request.localNodeName,
            messageHash = ReactionParser.generateMessageHash(message.text, message.reactionTimestamp),
            rawText = rawText,
            receivedAt = state.now(),
            radioID = message.radioID,
            channelIndex = channelIndex,
            contactID = contactID,
        )
    }
}

/** The emoji, message, and send dependencies one outgoing reaction shares. */
private data class ReactionSendRequest(
    val emoji: String,
    val message: MessageDto,
    val localNodeName: String,
    val store: ReactionStore,
    val sender: ReactionSender,
)

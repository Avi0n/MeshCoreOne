package one.meshcore.shared.chat.reactions

import one.meshcore.shared.model.Uuid

/**
 * The fields a reaction notification needs. [contactID] is set for a DM reaction, and [channelIndex] with
 * [radioID] for a channel reaction.
 */
data class ReactionNotice(
    val reactorName: String,
    val emoji: String,
    val messagePreview: String,
    val messageID: Uuid,
    val contactID: Uuid?,
    val channelIndex: Int?,
    val radioID: Uuid,
)

/**
 * Reports a freshly persisted reaction to a message the local node sent, so the platform can notify.
 * [ReactionHandler] calls this only after confirming the target message was outgoing, the reactor is not
 * the local node, and the conversation is not muted.
 */
fun interface ReactionNotifier {
    suspend fun notifyReaction(notice: ReactionNotice)

    companion object {
        val NoOp = ReactionNotifier { _ -> }
    }
}

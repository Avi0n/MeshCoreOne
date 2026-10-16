package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * The outbound seam a reaction is sent through: a reaction is an ordinary text message, so both methods
 * route to the same send path any chat text uses. Injected so the reaction layer never reaches into the
 * message service directly and stays unit-testable. Mirrors the `messageService.sendChannelMessage` /
 * `sendDirectMessage` calls in the Swift `ChatViewModel` reaction send. A throw signals a send failure,
 * which the caller surfaces (no optimistic persist happens).
 */
interface ReactionSender {
    /** Sends [text] as a channel message on [channelIndex] of [radioID]. */
    suspend fun sendChannelReaction(text: String, channelIndex: Int, radioID: Uuid)

    /** Sends [text] as a direct message to [contact]. */
    suspend fun sendDirectReaction(text: String, contact: ContactDto)
}

/** Notified after a message's reaction summary changes, so the open thread refreshes that row in place. */
fun interface ReactionSummarySink {
    /** [summary] is the freshly rebuilt cache string for [messageID]. */
    suspend fun onReactionSummary(messageID: Uuid, summary: String)
}

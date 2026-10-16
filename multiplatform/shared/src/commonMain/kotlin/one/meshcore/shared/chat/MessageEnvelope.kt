package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/**
 * Per-message identity, direction, status, and sender — everything not specific to one content fragment.
 * Fully structural equality: every field participates, so a change in any of them makes two envelopes
 * unequal and re-renders the row. Mirrors the Swift `MessageEnvelope`.
 *
 * [dateEpochSeconds] is the sender's send time (not the drain/receive time): the centered day-divider is
 * the sole time surface, so a days-old drained message must not be relabelled at its delivery time.
 *
 * [hasFailed] is derived from [status] rather than stored, so a `status`-only copy can never leave the two
 * out of sync; it is fully determined by [status], which already participates in equality.
 */
data class MessageEnvelope(
    val messageID: Uuid,
    val isOutgoing: Boolean,
    val senderName: String,
    val senderResolution: NodeNameResolution,
    val status: MessageStatus,
    val dateEpochSeconds: Double,
    val containsSelfMention: Boolean,
    val mentionSeen: Boolean,
    val incomingAvatar: IncomingAvatarIdentity? = null,
) {
    val hasFailed: Boolean get() = status == MessageStatus.FAILED
}

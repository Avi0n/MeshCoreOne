package one.meshcore.shared.room.ui

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_status_delivered
import one.meshcore.shared.resources.chats_message_status_failed
import one.meshcore.shared.resources.chats_message_status_retrying
import one.meshcore.shared.resources.chats_message_status_sending
import one.meshcore.shared.resources.chats_message_status_sent
import org.jetbrains.compose.resources.StringResource

/**
 * Combined room-bubble announcement: incoming leads with the author, outgoing appends delivery
 * status, matching iOS `RoomMessageBubble.accessibilityMessageLabel`.
 */
internal fun roomMessageAccessibilityLabel(author: String, outgoing: Boolean, body: String, status: String?): String =
    if (outgoing) {
        if (status.isNullOrEmpty()) body else "$body, $status"
    } else {
        if (author.isEmpty()) body else "$author: $body"
    }

/** Visible Retry on failed outgoing room rows, matching iOS `StatusIndicator`. */
internal fun roomOutgoingShowsRetry(status: MessageStatus): Boolean = status == MessageStatus.FAILED

/** Red failed glyph beside the status caption, matching iOS `exclamationmark.circle`. */
internal fun roomOutgoingShowsFailedGlyph(status: MessageStatus): Boolean = status == MessageStatus.FAILED

/**
 * Visible status caption under an outgoing room bubble, matching iOS `RoomMessageDTO.localizedStatusText`.
 * TalkBack uses the screen's accessibility status mapping rather than these Chat strings.
 */
internal fun roomOutgoingStatusCaption(status: MessageStatus): StringResource = when (status) {
    MessageStatus.PENDING, MessageStatus.SENDING -> Res.string.chats_message_status_sending
    MessageStatus.SENT -> Res.string.chats_message_status_sent
    MessageStatus.DELIVERED -> Res.string.chats_message_status_delivered
    MessageStatus.FAILED -> Res.string.chats_message_status_failed
    MessageStatus.RETRYING -> Res.string.chats_message_status_retrying
}

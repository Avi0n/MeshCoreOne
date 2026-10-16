package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.math.max

/**
 * Computes the id of the message the "new messages" divider sits above, or null when no divider shows.
 * Pure port of the Swift `ChatViewModel.computeDividerPosition`; the thread calls it once per conversation
 * open, on the UNFILTERED fetched page (before outgoing-reaction filtering) so the positional index stays
 * aligned with the array the unread count was measured against.
 *
 * Positional by design (spec §9.4): the divider sits [unreadCount] rows from the tail, relying on unread
 * rows occupying the array tail (upheld by block-at-reconnect: each unread row takes a `sortDate` at or
 * after its receive/drain time). A `first { !isRead }` scan is deliberately NOT used — per-message `isRead`
 * is not maintained on chat-open, only the unread counter is cleared.
 *
 * If the boundary row is a hidden outgoing reaction, the index advances toward newer to the next visible
 * row so the divider keeps the same visual position.
 */
fun computeNewMessagesDivider(
    messages: List<MessageDto>,
    unreadCount: Int,
    isDM: Boolean,
    isHiddenReaction: (MessageDto, Boolean) -> Boolean = { _, _ -> false },
): Uuid? {
    if (unreadCount <= ChatThreadConstants.NEW_MESSAGES_DIVIDER_THRESHOLD) return null
    var index = max(0, messages.size - unreadCount)
    while (index < messages.size && isHiddenReaction(messages[index], isDM)) {
        index += 1
    }
    return messages.getOrNull(index)?.id
}

/**
 * Whether a message drops out of the visible timeline as an already-sent outgoing reaction. A failed
 * reaction stays visible so the user can retry it. The predicate keeps divider math and page filtering
 * aligned.
 */
fun isHiddenOutgoingReaction(message: MessageDto, isReaction: (MessageDto) -> Boolean): Boolean {
    if (!message.isOutgoing) return false
    if (message.status == MessageStatus.FAILED) return false
    return isReaction(message)
}

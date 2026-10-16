package one.meshcore.shared.chat

import kotlinx.coroutines.launch
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/**
 * Reports whether the list is near the bottom (spec §9.5 gate). Landing at the bottom clears the
 * scrolled-up unread badge; the Compose side derives this from the first visible index.
 */
fun ChatThreadViewModel.setNearBottom(atBottom: Boolean) {
    if (isAtBottom == atBottom) return
    isAtBottom = atBottom
    if (atBottom) unreadWhileScrolledUp = 0
    publish()
}

/** Jumps to the newest row (scroll-to-bottom affordance). Always lands, even when scrolled up. */
fun ChatThreadViewModel.requestScrollToBottom() {
    landAtBottom()
    publish()
}

/**
 * Re-pins the newest row only while already at the bottom. Composer focus uses this so a
 * scrolled-up thread stays put, matching iOS `scrollToBottomRequest`.
 */
fun ChatThreadViewModel.requestPinToBottom() {
    if (!isAtBottom) return
    landAtBottom()
    publish()
}

/** Requests a one-shot scroll to the new-messages divider, if one is placed. */
fun ChatThreadViewModel.requestScrollToDivider() {
    val id = dividerMessageID ?: return
    scrollToken += 1
    scrollCommand = ScrollCommand.ToMessage(id, scrollToken)
    publish()
}

/** Requests a one-shot scroll to [messageId], an unseen self-mention. */
fun ChatThreadViewModel.requestScrollToMention(messageId: Uuid) = scrollToMessage(messageId)

/** Requests a one-shot scroll to [messageId]. A later request outranks an earlier one by token. */
internal fun ChatThreadViewModel.scrollToMessage(messageId: Uuid) {
    scrollToken += 1
    scrollCommand = ScrollCommand.ToMessage(messageId, scrollToken)
    publish()
}

/**
 * An incoming mention on the open thread is seen immediately, matching iOS
 * `handleIncomingMentionIfNeeded`. Scroll position does not matter: the conversation is on
 * screen, so the mention must not keep a list badge or jump affordance.
 */
internal fun ChatThreadViewModel.markOpenThreadMentionSeen(message: MessageDto) {
    if (message.containsSelfMention && !message.mentionSeen) onMentionSeen(message.id)
}

/**
 * Persists that the self-mention at [messageId] has scrolled into view, then updates the rendered
 * item in place so the scroll-to-mention affordance's off-screen count drops without a full reload.
 * Mirrors iOS `markMentionSeen`.
 */
fun ChatThreadViewModel.onMentionSeen(messageId: Uuid) {
    val item = coordinator.renderState.items.firstOrNull { it.id == messageId } ?: return
    if (!item.envelope.containsSelfMention || item.envelope.mentionSeen) return
    scope.launch {
        runChatThreadStatusWrite(scope) { dependencies.store()?.markMentionSeen(messageId) }
        writer?.applyMentionSeen(messageId)
        publish()
    }
}

package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.text.MentionUtilities

/**
 * Mention target for a chat reply, mirroring iOS `ChatConversationView.handleReply`.
 * A DM mentions the contact's stored name. A channel mentions the wire `senderNodeName`, falling back
 * to the localized unknown — not a prefix-resolved contact book name, which would ping the wrong node.
 */
internal fun chatReplyMentionName(
    isChannel: Boolean,
    senderNodeName: String?,
    contactName: String?,
    unknownLabel: String,
): String = if (isChannel) {
    senderNodeName?.takeIf { it.isNotEmpty() } ?: unknownLabel
} else {
    contactName?.takeIf { it.isNotEmpty() } ?: unknownLabel
}

/**
 * Reply-text choice, mirroring iOS `handleReply`: quoting on replaces the draft with a quoted preview of
 * the stored payload (so a leading `@[…]` can still be stripped); quoting off (the default) appends a
 * bare @name to the existing draft.
 */
internal fun chatReplyText(
    mentionName: String,
    messageText: String,
    currentDraft: String,
    replyWithQuote: Boolean,
): String = if (replyWithQuote) {
    MentionUtilities.buildReplyText(mentionName, messageText)
} else {
    MentionUtilities.appendMention(mentionName, currentDraft)
}

package one.meshcore.shared.room.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.text.MentionUtilities

/** In-thread Connect always presents auth; a missing contact row is Not Found, matching iOS. */
internal fun roomConversationAuthShowsNotFound(contact: ContactDto?): Boolean = contact == null

/**
 * The reply-text choice for a room message, mirroring iOS RoomConversationView.handleReply: quoting on
 * replaces the draft with a quoted preview; quoting off (the default) appends a bare @name to the existing
 * draft, preserving typed text.
 */
internal fun roomReplyText(name: String, messageText: String, currentDraft: String, replyWithQuote: Boolean): String =
    if (replyWithQuote) {
        MentionUtilities.buildReplyText(name, messageText)
    } else {
        MentionUtilities.appendMention(name, currentDraft)
    }

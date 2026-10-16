package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * Clears a direct conversation's messages without deleting the contact. Zeroes both unread counters
 * but preserves `lastMessageDate` so the now-empty conversation stays in the chats list showing "No
 * messages" (unlike delete-conversation, which nils the date). Mirrors `clearContactMessages`.
 */
suspend fun ContactService.clearContactMessages(contactID: Uuid) = contactMessageClearer.clear(contactID)

package one.meshcore.shared.notification

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * Shade reply sends on the radio (iOS `MessageService.sendDirectMessage` / `sendChannelMessage`),
 * not the durable queue. Enqueue-and-return would clear unread before a later drain failure.
 */
suspend fun sendQuickReplyOnRadio(
    target: ConversationTarget,
    fetchContact: suspend (Uuid) -> ContactDto?,
    isConnectionReady: (Uuid) -> Boolean,
    sendDirect: suspend (ContactDto) -> Unit,
    sendChannel: suspend (index: Int, radioID: Uuid) -> Unit,
): Boolean = when (target) {
    is ConversationTarget.Direct -> {
        val contact = fetchContact(target.contactID)
        if (contact == null || !isConnectionReady(contact.radioID)) {
            false
        } else {
            sendDirect(contact)
            true
        }
    }
    is ConversationTarget.Channel -> {
        if (!isConnectionReady(target.radioID)) {
            false
        } else {
            sendChannel(target.channelIndex, target.radioID)
            true
        }
    }
    is ConversationTarget.Room -> false
}

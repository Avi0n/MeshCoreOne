package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.Uuid

/** The message, repeat, and repeater lookups for one path-detail load. */
internal data class ThreadPathFetches(
    val messageId: Uuid,
    val fetchMessage: suspend (Uuid) -> MessageDto?,
    val fetchRepeats: suspend (Uuid) -> List<MessageRepeatDto>,
    val contactService: ContactService?,
    val radioId: Uuid?,
    val fetchDiscoveredRepeaters: suspend (Uuid) -> List<DiscoveredNodeRecord>,
)

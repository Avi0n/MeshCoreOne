package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.MessageService
import one.meshcore.shared.chat.sendChannelMessage
import one.meshcore.shared.chat.sendDirectMessage
import one.meshcore.shared.model.Uuid

/**
 * [ReactionSender] that routes reaction wire text through [MessageService] as ordinary chat sends
 * (channel or DM). Matches the Swift path where reactions are plain text messages on the same wire.
 */
class MessageServiceReactionSender(private val messageService: () -> MessageService?) : ReactionSender {
    override suspend fun sendChannelReaction(text: String, channelIndex: Int, radioID: Uuid) {
        val service = messageService() ?: error("message service unavailable")
        service.sendChannelMessage(text = text, channelIndex = channelIndex, radioID = radioID)
    }

    override suspend fun sendDirectReaction(text: String, contact: ContactDto) {
        val service = messageService() ?: error("message service unavailable")
        service.sendDirectMessage(text = text, to = contact)
    }
}

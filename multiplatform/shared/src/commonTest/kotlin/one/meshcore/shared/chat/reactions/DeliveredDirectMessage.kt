package one.meshcore.shared.chat.reactions

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid

/** A delivered "Hello world" direct message, so a reaction test can vary only the contact and direction. */
internal fun deliveredDirectMessage(contactID: Uuid, direction: MessageDirection = MessageDirection.INCOMING) =
    ChatFixtures.message(
        text = "Hello world",
        timestamp = 1_704_067_200L,
        direction = direction,
        status = MessageStatus.DELIVERED,
        contactID = contactID,
        channelIndex = null,
    )

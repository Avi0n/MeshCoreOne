// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageActionAvailability.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageAction.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.feature.chats.actions.MessageAction

data class RoomMessageActionAvailability(
    val canReply: Boolean,
    val canSendDirectMessage: Boolean,
    val canSendAgain: Boolean,
) {
    val actions: List<MessageAction> get() = buildList {
        if (canReply) add(MessageAction.Reply)
        if (canSendDirectMessage) add(MessageAction.SendDirectMessage)
        add(MessageAction.Copy)
        if (canSendAgain) add(MessageAction.SendAgain)
    }

    companion object {
        fun forMessage(message: RoomMessageDTO, session: RemoteNodeSessionDTO) =
            RoomMessageActionAvailability(
                canReply = !message.isFromSelf && session.canPost,
                canSendDirectMessage = !message.isFromSelf && message.authorName != null,
                canSendAgain = message.isFromSelf,
            )
    }
}

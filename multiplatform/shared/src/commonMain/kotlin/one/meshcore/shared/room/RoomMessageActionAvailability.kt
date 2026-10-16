package one.meshcore.shared.room

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.remotenode.canPost

/** An action a user can take on a room message from its long-press sheet. Mirrors Swift `RoomMessageAction`. */
enum class RoomMessageAction {
    COPY,
    TRANSLATE,
    REPLY,
    SEND_DM,
    SEND_AGAIN,
}

/**
 * Which actions a room message exposes, mirroring the Swift `RoomMessageActionAvailability`. Computed once
 * at presentation so the buttons stay stable while the sheet is open, even if the session's permission
 * changes underneath. Copy is always available; the rest are conditional.
 */
data class RoomMessageActionAvailability(val canReply: Boolean, val canSendDm: Boolean, val canSendAgain: Boolean) {
    /** The ordered actions to show, matching iOS `RoomMessageActionsSheet` button order. */
    val availableActions: List<RoomMessageAction>
        get() = buildList {
            if (canReply) add(RoomMessageAction.REPLY)
            if (canSendDm) add(RoomMessageAction.SEND_DM)
            add(RoomMessageAction.COPY)
            add(RoomMessageAction.TRANSLATE)
            if (canSendAgain) add(RoomMessageAction.SEND_AGAIN)
        }

    companion object {
        /**
         * Reply needs a postable session and a foreign author; Send-DM needs a foreign author we can name;
         * Send-again is only for our own messages.
         */
        fun of(message: RoomMessageDto, session: RemoteNodeSessionDto): RoomMessageActionAvailability =
            RoomMessageActionAvailability(
                canReply = !message.isFromSelf && session.canPost,
                canSendDm = !message.isFromSelf && message.authorName != null,
                canSendAgain = message.isFromSelf,
            )
    }
}

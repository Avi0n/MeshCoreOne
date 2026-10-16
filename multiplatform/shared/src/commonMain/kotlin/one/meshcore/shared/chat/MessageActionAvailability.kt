package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto

/** An action a user can take on a chat message from its long-press sheet. Mirrors Swift `MessageAction`. */
enum class MessageAction {
    REPLY,
    SEND_DM,
    COPY,
    TRANSLATE,
    SEND_AGAIN,
    BLOCK_SENDER,
    DELETE,
}

/**
 * Which long-press message actions are available for a [MessageDto]. Mirrors Swift
 * `MessageActionAvailability` so Android sheets stay action-set-identical to iOS.
 */
data class MessageActionAvailability(
    val canReply: Boolean,
    val canCopy: Boolean,
    val canSendAgain: Boolean,
    val canBlockSender: Boolean,
    val canSendDM: Boolean,
    val canShowRepeatDetails: Boolean,
    val canViewPath: Boolean,
    val canDelete: Boolean,
    val canShowIncomingPaths: Boolean = false,
) {
    /** Ordered primary buttons, matching iOS `ActionsButtonsSection`. */
    val primaryActions: List<MessageAction>
        get() = buildList {
            if (canReply) add(MessageAction.REPLY)
            if (canSendDM) add(MessageAction.SEND_DM)
            add(MessageAction.COPY)
            add(MessageAction.TRANSLATE)
            if (canSendAgain) add(MessageAction.SEND_AGAIN)
        }

    /** Ordered destructive buttons, matching iOS `ActionsDestructiveSection`. */
    val destructiveActions: List<MessageAction>
        get() = buildList {
            if (canBlockSender) add(MessageAction.BLOCK_SENDER)
            if (canDelete) add(MessageAction.DELETE)
        }

    companion object {
        fun of(message: MessageDto): MessageActionAvailability {
            val outgoing = message.isOutgoing
            val hasChannelSender =
                message.isChannelMessage && !outgoing && message.senderNodeName != null
            return MessageActionAvailability(
                canReply = !outgoing,
                canCopy = true,
                canSendAgain = outgoing,
                canBlockSender = hasChannelSender,
                canSendDM = hasChannelSender,
                canShowRepeatDetails = outgoing && message.heardRepeats > 0,
                canViewPath = !outgoing &&
                    message.isFloodRouted &&
                    message.pathNodes?.isNotEmpty() == true,
                canDelete = true,
                canShowIncomingPaths = !outgoing && message.heardRepeats > 0,
            )
        }
    }
}

/**
 * Long-press actions prefer the freshly fetched DTO so Path Details appear once hop hashes
 * have landed. A hop-less fetch cannot hide those actions if the bubble was already rebaked with hops.
 */
fun messageActionsAvailability(baked: MessageActionAvailability, fetched: MessageDto?): MessageActionAvailability {
    val live = fetched?.let { MessageActionAvailability.of(it) } ?: return baked
    return live.copy(
        canViewPath = live.canViewPath || baked.canViewPath,
        canShowIncomingPaths = live.canShowIncomingPaths || baked.canShowIncomingPaths,
    )
}

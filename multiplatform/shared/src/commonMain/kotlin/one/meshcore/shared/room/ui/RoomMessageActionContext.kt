package one.meshcore.shared.room.ui

import one.meshcore.shared.backup.RoomMessageDto

/**
 * The environment a [one.meshcore.shared.room.RoomMessageAction] dispatches against: the tapped message
 * plus the room's live handlers (view model, clipboard, open-chat, and the reply-quote preference).
 */
internal data class RoomMessageActionContext(
    val message: RoomMessageDto,
    val viewModel: RoomConversationViewModel,
    val copyText: (String) -> Unit,
    val onSendDm: (String) -> Unit,
    val replyWithQuote: Boolean,
    val displayedText: String,
    val onTranslate: () -> Unit,
)

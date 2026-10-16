package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.chat.MessageItem
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.model.Uuid

/** Immutable values rendered by [ChatThreadScreen]. */
internal data class ChatThreadScreenState(
    val thread: ChatThreadRenderState,
    val composer: ComposerState,
    val title: String,
    val isReady: Boolean,
    val isEncrypted: Boolean,
    val channel: ChannelDto? = null,
    val contact: ContactDto? = null,
    val isNetworkOffline: Boolean = false,
)

/** Queries and navigation callbacks supplied at the thread screen boundary. */
internal data class ChatThreadScreenDependencies(
    val onLinkTap: (LinkTarget) -> Unit = {},
    val fetchMessage: suspend (Uuid) -> MessageDto? = { null },
    val fetchRepeats: suspend (Uuid) -> List<MessageRepeatDto> = { emptyList() },
    val previews: MessagePreviewActions = MessagePreviewActions(),
    val onChannelDeleted: () -> Unit = {},
    val onChannelMessagesCleared: () -> Unit = {},
    val onChannelSenderBlocked: () -> Unit = {},
    val onChannelInfoDismiss: () -> Unit = {},
    val onOpenContactChat: (String) -> Unit = {},
    val onOpenContactInfo: (String) -> Unit = {},
    val translationPhase: (Uuid) -> MessageTranslationPhase? = { null },
    val onTranslate: (MessageItem) -> Unit = {},
    val onSheetTranslate: (MessageItem) -> Unit = onTranslate,
    /** False when the coordinator no longer holds a DTO for this row (iOS `resolver.message`). */
    val messageAvailable: (Uuid) -> Boolean = { true },
)

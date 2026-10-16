package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.model.Uuid

internal data class ConversationListTestHooks(
    val isReady: Boolean = true,
    val isReaction: (MessageDto) -> Boolean = { false },
    val channelService: () -> ChannelService? = { null },
    val clearChannelNotifications: suspend (Int, Uuid) -> Unit = { _, _ -> },
    val updateBadge: suspend () -> Unit = {},
)

internal fun conversationListViewModel(
    store: FakeConversationListStore,
    hooks: ConversationListTestHooks = ConversationListTestHooks(),
    errorMessages: () -> ChatErrorMessages = { ChatErrorMessages.developerEnglish() },
): ConversationListViewModel = ConversationListViewModel(
    dependencies = ConversationListViewModel.Dependencies(
        errorMessages = errorMessages,
        store = { store },
        radioId = { ChatFixtures.RADIO },
        isReady = { hooks.isReady },
        isReaction = hooks.isReaction,
        channelService = hooks.channelService,
        clearChannelNotifications = hooks.clearChannelNotifications,
        updateBadge = hooks.updateBadge,
    ),
)

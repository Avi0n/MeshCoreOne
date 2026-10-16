package one.meshcore.shared.chat

import one.meshcore.shared.channel.displayName
import one.meshcore.shared.map.containsIgnoringCaseAndDiacritics

/** Filter categories for the conversation list. Mirrors the Swift `ChatFilter` enum. */
enum class ChatFilter {
    ALL,
    UNREAD,
    DIRECT_MESSAGES,
    CHANNELS,
    ROOMS,
}

/**
 * Filters conversations by category and search text. A non-empty [searchText] ignores [filter] and
 * matches [Conversation.displayName] across all conversations. An unnamed channel uses
 * [unnamedChannelName] (iOS `ChannelDTO.displayName`). `UNREAD` keeps unmuted conversations with unread
 * messages; the kind filters keep only that case. Mirrors Swift `[Conversation].filtered(by:searchText:)`.
 */
fun List<Conversation>.filtered(
    filter: ChatFilter,
    searchText: String,
    unnamedChannelName: (Int) -> String,
): List<Conversation> {
    if (searchText.isNotEmpty()) {
        return this.filter { conversation ->
            conversation.searchName(unnamedChannelName).containsIgnoringCaseAndDiacritics(searchText)
        }
    }

    return when (filter) {
        ChatFilter.ALL -> this
        ChatFilter.UNREAD -> this.filter { it.unreadCount > 0 && !it.isMuted }
        ChatFilter.DIRECT_MESSAGES -> this.filter { it is Conversation.Direct }
        ChatFilter.CHANNELS -> this.filter { it is Conversation.Channel }
        ChatFilter.ROOMS -> this.filter { it is Conversation.Room }
    }
}

private fun Conversation.searchName(unnamedChannelName: (Int) -> String): String = when (this) {
    is Conversation.Channel -> channel.displayName(unnamedChannelName)
    else -> displayName
}

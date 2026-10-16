package one.meshcore.shared.navigation

import one.meshcore.shared.chat.ChatScrollRequest
import one.meshcore.shared.model.Uuid

/** Opens a direct conversation with [contactId] on the Chats tab. */
fun NavigationCoordinator.navigateToChat(
    contactId: String,
    scrollToMessageId: String? = null,
    displayTitle: String? = null,
) {
    openChatRoute(ChatRoute.Direct(contactId, displayTitle = displayTitle), scrollToMessageId)
}

/** Opens a contact that does not have a conversation row until its first message is sent. */
fun NavigationCoordinator.navigateToNewChat(contactId: String, displayTitle: String? = null) {
    openChatRoute(ChatRoute.Direct(contactId, displayTitle = displayTitle), scrollToMessageId = null)
}

/** Opens a channel conversation on the Chats tab. */
fun NavigationCoordinator.navigateToChannel(
    channelId: String,
    scrollToMessageId: String? = null,
    displayTitle: String? = null,
) {
    openChatRoute(ChatRoute.Channel(channelId, displayTitle = displayTitle), scrollToMessageId)
}

/**
 * Opens a room conversation on the Chats tab, or — when [isConnected] is false — stages
 * [pendingRoomAuth] with the session id instead of opening the route. Mirrors iOS
 * `ChatsView.navigate`'s room-reauth branch: a disconnected room's list or notification tap must gate
 * through the auth sheet rather than opening the thread un-authenticated.
 */
fun NavigationCoordinator.navigateToRoom(
    sessionId: String,
    displayTitle: String? = null,
    isConnected: Boolean = true,
) {
    if (!isConnected) {
        replaceConversationIntents()
        selectedTabIndex.value = AppTab.CHATS.index
        presentDisconnectedRoom(sessionId)
        return
    }
    openChatRoute(ChatRoute.Room(sessionId, displayTitle = displayTitle), scrollToMessageId = null)
}

/**
 * Pops the open Chats detail when it matches [matches]. Used after a list delete so a wide
 * shell cannot keep sending into a conversation the user just removed (iOS
 * `ChatListActions.clearNavigationIfActive`).
 */
fun NavigationCoordinator.clearChatRouteIfMatching(matches: (ChatRoute) -> Boolean) {
    val current = chatsRouteState.value ?: return
    if (matches(current)) pop(AppTab.CHATS)
}

private fun NavigationCoordinator.openChatRoute(route: ChatRoute, scrollToMessageId: String?) {
    // Hide the tab bar BEFORE switching so it does not flash during the push.
    tabBarVisibleState.value = false
    chatsRouteState.value = route
    replaceConversationIntents()
    pendingScrollState.value = scrollToMessageId?.let {
        ChatScrollRequest(Uuid.random(), route.conversationId, Uuid.normalized(it))
    }
    val key = NavKey.ChatDetail(route)
    dropChatsContactInfoIfPresent()
    val stack = backStackOf(AppTab.CHATS)
    when {
        // Same destination already on top — keep stack, still consume scroll target above.
        (stack.lastOrNull() as? NavKey.ChatDetail)?.route?.conversationId == route.conversationId -> {
            backStackState.value = backStackState.value + (AppTab.CHATS to (stack.dropLast(1) + key))
        }
        // Root list → first chat: depth-1 entry.
        stack.isEmpty() -> replaceTop(AppTab.CHATS, key)
        // Chat A → chat B (or channel): push so Back returns to A, not the list.
        else -> pushChatDetail(key)
    }
    selectedTabIndex.value = AppTab.CHATS.index
}

/**
 * Appends a chat destination, capping depth so rapid cross-chat navigation cannot grow without
 * bound. Drops the oldest entry when full (keeps the root-most path as short as possible).
 */
private fun NavigationCoordinator.pushChatDetail(key: NavKey.ChatDetail) {
    val stack = backStackOf(AppTab.CHATS)
    val base = if (stack.size >= NavigationCoordinator.MAX_CHAT_BACK_STACK_DEPTH) {
        stack.drop(stack.size - NavigationCoordinator.MAX_CHAT_BACK_STACK_DEPTH + 1)
    } else {
        stack
    }
    backStackState.value = backStackState.value + (AppTab.CHATS to (base + key))
}

internal fun NavigationCoordinator.replaceConversationIntents() {
    pendingScrollState.value = null
    pendingRoomAuthState.value = null
}

/** A stale delivery cannot consume a newer tap, even when it names the same message. */
fun NavigationCoordinator.consumePendingScrollRequest(requestID: Uuid): Boolean {
    if (pendingScrollState.value?.requestID != requestID) return false
    pendingScrollState.value = null
    return true
}

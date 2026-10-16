package one.meshcore.shared.navigation

import one.meshcore.shared.model.Uuid

/** Refreshes a selected payload without pushing history or consuming pending notification intents. */
fun NavigationCoordinator.reconcileSelectedChatRoute(
    retainingDirectContactIDs: Set<Uuid>?,
    refresh: (ChatRoute) -> ChatRoute?,
) {
    val current = chatsRouteState.value ?: return
    val refreshed = refresh(current)
    when {
        refreshed == null -> {
            val retainDirect = current is ChatRoute.Direct &&
                (retainingDirectContactIDs == null || Uuid.normalized(current.contactId) in retainingDirectContactIDs)
            if (!retainDirect) closeChatsSelection()
        }
        current.roomConnection() == true && refreshed.roomConnection() == false ->
            presentDisconnectedRoom((refreshed as ChatRoute.Room).sessionId)
        else -> {
            chatsRouteState.value = refreshed
            backStackState.value = backStackState.value.mapValues { (tab, stack) ->
                if (tab != AppTab.CHATS) {
                    stack
                } else {
                    stack.map { key ->
                        if (key is NavKey.ChatDetail && key.route.conversationId == current.conversationId) {
                            key.copy(route = refreshed)
                        } else {
                            key
                        }
                    }
                }
            }
        }
    }
}

internal fun NavigationCoordinator.presentDisconnectedRoom(sessionId: String) {
    closeChatsSelection()
    pendingRoomAuthState.value = sessionId
}

private fun NavigationCoordinator.closeChatsSelection() {
    backStackState.value = backStackState.value - AppTab.CHATS
    chatsRouteState.value = null
    tabBarVisibleState.value = true
}

private fun ChatRoute.roomConnection(): Boolean? = (this as? ChatRoute.Room)?.isConnected

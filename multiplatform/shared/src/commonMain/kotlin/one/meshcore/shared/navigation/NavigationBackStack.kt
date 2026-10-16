package one.meshcore.shared.navigation

/** The back stack for [tab]; empty means the tab is at its root list. */
fun NavigationCoordinator.backStackOf(tab: AppTab): List<NavKey> = backStackState.value[tab] ?: emptyList()

/** Whether [tab] has a pushed destination system-back can pop. */
fun NavigationCoordinator.canPop(tab: AppTab): Boolean = backStackOf(tab).isNotEmpty()

/** Drops an in-thread contact-info overlay so a later chat push is not stacked on top of it. */
internal fun NavigationCoordinator.dropChatsContactInfoIfPresent() {
    val stack = backStackOf(AppTab.CHATS)
    val top = stack.lastOrNull() as? NavKey.ContactDetail ?: return
    if (!top.fromDirectChat) return
    val trimmed = stack.dropLast(1)
    backStackState.value = if (trimmed.isEmpty()) {
        backStackState.value - AppTab.CHATS
    } else {
        backStackState.value + (AppTab.CHATS to trimmed)
    }
}

/** Pushes [key] onto [tab]'s back stack. */
fun NavigationCoordinator.push(tab: AppTab, key: NavKey) {
    backStackState.value = backStackState.value + (tab to (backStackOf(tab) + key))
}

/** Pops [tab]'s top destination; a no-op at the root. Returns whether anything was popped. */
fun NavigationCoordinator.pop(tab: AppTab): Boolean {
    val stack = backStackOf(tab)
    if (stack.isEmpty()) return false
    val newStack = stack.dropLast(1)
    backStackState.value = if (newStack.isEmpty()) {
        backStackState.value - tab
    } else {
        backStackState.value + (tab to newStack)
    }
    if (newStack.isEmpty()) {
        clearHoistedSelectionFor(tab)
    } else {
        // Multi-depth chats (and any future multi-entry stacks) restore the remaining top.
        restoreHoistedFromStackTop(tab, newStack.last())
    }
    return true
}

private fun NavigationCoordinator.restoreHoistedFromStackTop(tab: AppTab, key: NavKey) {
    when (tab) {
        AppTab.CHATS -> {
            if (key is NavKey.ChatDetail) {
                chatsRouteState.value = key.route
                tabBarVisibleState.value = false
            }
        }
        AppTab.NODES -> {
            when (key) {
                is NavKey.ContactDetail -> {
                    selectedContactState.value = key.contactId
                    nodesDiscoveryState.value = false
                    nodesBlockedState.value = false
                }
                is NavKey.Discovery -> {
                    selectedContactState.value = null
                    nodesDiscoveryState.value = true
                    nodesBlockedState.value = false
                }
                is NavKey.BlockedContacts -> {
                    selectedContactState.value = null
                    nodesDiscoveryState.value = false
                    nodesBlockedState.value = true
                }
                else -> Unit
            }
        }
        AppTab.MAP, AppTab.TOOLS, AppTab.SETTINGS -> Unit
    }
}

/** Clears [tab]'s back stack back to its root list (and any in-tab tool/setting). */
fun NavigationCoordinator.popToRoot(tab: AppTab) {
    val hadStack = backStackOf(tab).isNotEmpty()
    val hadTool = tab == AppTab.TOOLS && selectedToolState.value != null
    val hadSetting = tab == AppTab.SETTINGS && selectedSettingState.value != null
    if (!hadStack && !hadTool && !hadSetting) return
    if (hadStack) {
        backStackState.value = backStackState.value - tab
    }
    clearHoistedSelectionFor(tab)
}

private fun NavigationCoordinator.clearHoistedSelectionFor(tab: AppTab) {
    when (tab) {
        AppTab.CHATS -> {
            chatsRouteState.value = null
            tabBarVisibleState.value = true
        }
        AppTab.NODES -> {
            selectedContactState.value = null
            nodesDiscoveryState.value = false
            nodesBlockedState.value = false
        }
        AppTab.TOOLS -> selectTool(null)
        AppTab.SETTINGS -> selectedSettingState.value = null
        AppTab.MAP -> restoreTabAfterMapFocus()
    }
}

/** After popping [NavKey.MapFocus], return to the originating tab when one was recorded. */
private fun NavigationCoordinator.restoreTabAfterMapFocus() {
    pendingMapFocusState.value = null
    val returnIndex = mapReturnTabIndex
    mapReturnTabIndex = null
    if (returnIndex == null) return
    val returnTab = AppTab.fromIndex(returnIndex)
    selectedTabIndex.value = returnTab.index
    // Re-hide the bar when returning into an open chat so chrome matches openChatRoute.
    if (returnTab == AppTab.CHATS && chatsRouteState.value != null) {
        tabBarVisibleState.value = false
    }
}

// Cross-tab / list-root navigations (Discovery, Blocked, ContactDetail, MapFocus, first chat)
// replace the whole stack so Back returns to the tab root. Chat→chat uses [pushChatDetail].
internal fun NavigationCoordinator.replaceTop(tab: AppTab, key: NavKey) {
    backStackState.value = backStackState.value + (tab to listOf(key))
}

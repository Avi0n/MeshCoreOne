package one.meshcore.shared.navigation

import one.meshcore.shared.model.Uuid

/** Drops only a matching Nodes selection after its contact was confirmed removed. */
fun NavigationCoordinator.clearSelectedContactIfMatching(contactId: String) {
    val selected = selectedContactState.value ?: return
    if (Uuid.normalized(selected) != Uuid.normalized(contactId)) return
    selectedContactState.value = null
    backStackState.value = backStackState.value - AppTab.NODES
    nodesDiscoveryState.value = false
    nodesBlockedState.value = false
}

/** Switches to the Nodes tab showing the discovery screen. */
fun NavigationCoordinator.navigateToDiscovery() {
    tabBarVisibleState.value = true
    nodesBlockedState.value = false
    nodesDiscoveryState.value = true
    selectedContactState.value = null
    replaceTop(AppTab.NODES, NavKey.Discovery)
    selectedTabIndex.value = AppTab.NODES.index
}

/** Switches to the Nodes tab showing the blocked-contacts list. */
fun NavigationCoordinator.navigateToBlockedContacts() {
    tabBarVisibleState.value = true
    nodesDiscoveryState.value = false
    nodesBlockedState.value = true
    selectedContactState.value = null
    replaceTop(AppTab.NODES, NavKey.BlockedContacts)
    selectedTabIndex.value = AppTab.NODES.index
}

/** Switches to the Nodes tab's contact list. */
fun NavigationCoordinator.navigateToContacts() {
    tabBarVisibleState.value = true
    selectedTabIndex.value = AppTab.NODES.index
}

/** Opens [contactId]'s detail on the Nodes tab. */
fun NavigationCoordinator.navigateToContactDetail(contactId: String) {
    // Restore the bar when leaving a chat-origin path (mention tap) so the user is not stranded.
    tabBarVisibleState.value = true
    nodesDiscoveryState.value = false
    selectedContactState.value = contactId
    // Keep the blocked list under detail so Back returns to it (iOS NavigationLink).
    if (nodesBlockedState.value) {
        push(AppTab.NODES, NavKey.ContactDetail(contactId))
    } else {
        nodesBlockedState.value = false
        replaceTop(AppTab.NODES, NavKey.ContactDetail(contactId))
    }
    selectedTabIndex.value = AppTab.NODES.index
}

/**
 * Opens contact detail over the current DM, matching iOS `ContactDetailView(showFromDirectChat:)`.
 * Stays on the Chats stack so Back returns to the thread instead of switching to Nodes.
 */
fun NavigationCoordinator.navigateToContactDetailFromChat(contactId: String) {
    val stack = backStackOf(AppTab.CHATS)
    val top = stack.lastOrNull()
    if (top is NavKey.ContactDetail && top.contactId == contactId && top.fromDirectChat) {
        return
    }
    push(AppTab.CHATS, NavKey.ContactDetail(contactId, fromDirectChat = true))
}

/**
 * Switches to the Map tab and stages a focus request. Restores the tab bar and pushes a
 * [NavKey.MapFocus] marker so system Back returns to the originating tab (chat preferred).
 */
fun NavigationCoordinator.navigateToMap(latitude: Double, longitude: Double) {
    tabBarVisibleState.value = true
    droppedMapPinState.value = MapFocusRequest(latitude, longitude)
    pendingMapFocusState.value = MapFocusRequest(latitude, longitude)
    val fromTab = AppTab.fromIndex(selectedTabIndex.value)
    if (fromTab != AppTab.MAP) {
        mapReturnTabIndex = fromTab.index
        replaceTop(AppTab.MAP, NavKey.MapFocus)
    }
    selectedTabIndex.value = AppTab.MAP.index
}

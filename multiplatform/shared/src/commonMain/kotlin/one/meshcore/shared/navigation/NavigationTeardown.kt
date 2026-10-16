package one.meshcore.shared.navigation

fun NavigationCoordinator.clearDroppedMapPin() {
    droppedMapPinState.value = null
}

fun NavigationCoordinator.clearPendingContactLink() {
    pendingContactLinkState.value = null
}

fun NavigationCoordinator.clearPendingChannelLink() {
    pendingChannelLinkState.value = null
}

/** Consume-once clear for [pendingHashtagJoin], mirroring iOS `pendingHashtag = nil`. */
fun NavigationCoordinator.clearPendingHashtagJoin() {
    pendingHashtagState.value = null
}

/** Consume-once clear for [pendingRoomAuth], mirroring iOS `clearPendingRoomAuthentication`. */
fun NavigationCoordinator.clearPendingRoomAuth() {
    pendingRoomAuthState.value = null
}

/**
 * Clears the deep-link confirmations and every per-radio detail selection so a staged sheet cannot
 * re-present and a selection made on the previous radio cannot aim a detail pane at the wrong radio.
 * Runs on disconnect and on connection replacement.
 */
fun NavigationCoordinator.clearPendingLinks() {
    pendingContactLinkState.value = null
    pendingChannelLinkState.value = null
    pendingHashtagState.value = null
    pendingSendMessageState.value = null
    pendingSendAdvertState.value = null
    clearPerRadioSelection()
}

/**
 * Resets every radio-scoped detail selection. Runs on disconnect and on a radio-to-radio switch. The
 * offline Line of Sight tool is preserved because it is not radio-scoped.
 */
fun NavigationCoordinator.clearPerRadioSelection() {
    replaceConversationIntents()
    selectedContactState.value = null
    nodesDiscoveryState.value = false
    nodesBlockedState.value = false
    chatsRouteState.value = null
    mapReturnTabIndex = null
    popToRoot(AppTab.CHATS)
    popToRoot(AppTab.NODES)
    popToRoot(AppTab.MAP)
    radioSheetEpochState.value += 1
    clearPerDeviceSelection()
}

/**
 * Clears only device-scoped selections (a radio-requiring tool or My Device settings page), keeping
 * an open Chats/Nodes detail so a manual disconnect does not eject the user from an open conversation.
 */
fun NavigationCoordinator.clearPerDeviceSelection() {
    if (selectedToolState.value?.requiresRadio == true) {
        selectTool(null)
    }
    if (selectedSettingState.value?.requiresDevice == true) {
        selectedSettingState.value = null
    }
}

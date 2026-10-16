package one.meshcore.shared.ui

import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.pop
import one.meshcore.shared.room.ui.RoomDetailInputs

internal fun roomDetailInputs(
    sessionId: String,
    deps: ShellDependencies,
    deviceLocation: () -> LatLng?,
    onLinkTap: (one.meshcore.shared.chat.text.LinkTarget) -> Unit,
): RoomDetailInputs = RoomDetailInputs(
    sessionId = sessionId,
    roomServerService = deps.roomServerService,
    contactService = deps.contactService,
    radioId = deps.currentRadioId,
    conversationListStore = deps.conversationListStore,
    roomAdminService = deps.roomAdminService,
    nodePasswordStore = deps.nodePasswordStore,
    nodeSnapshotService = deps.nodeSnapshotService,
    roomNotifications = deps.roomNotifications,
    onConversationsChanged = deps.onConversationsChanged,
    updateBadge = deps.updateBadge,
    servicesChanged = deps.connectionState,
    appForeground = deps.appForeground,
    onBack = { deps.coordinator.pop(AppTab.CHATS) },
    onOpenContactChat = { contactId -> deps.coordinator.navigateToChat(contactId) },
    onLinkTap = onLinkTap,
    localNodeName = deps.localNodeName.value,
    locationService = deps.locationService,
    replyWithQuote = { deps.chatDisplayPreferences?.state?.value?.replyWithQuote ?: false },
    connectionState = deps.connectionState,
    presentSystemTranslation = deps.presentSystemTranslation,
    translationTargetPreferences = deps.translationTargetPreferences,
    mapDisplayPreferences = deps.mapDisplayPreferences,
    remoteAdminWorkspaces = deps.remoteAdminWorkspaces,
    discoveredNodeStore = deps.discoveredNodeStore,
    userLocation = {
        deviceLocation()?.let { UserLocation(it.latitude, it.longitude) }
    },
)

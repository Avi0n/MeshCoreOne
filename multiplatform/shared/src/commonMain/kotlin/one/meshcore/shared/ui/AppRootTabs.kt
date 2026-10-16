package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.ui.ConversationListInputs
import one.meshcore.shared.chat.ui.ConversationListRoot
import one.meshcore.shared.chat.ui.PendingChatScroll
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.ui.ContactDetailScreen
import one.meshcore.shared.contact.ui.ContactListInputs
import one.meshcore.shared.contact.ui.ContactListRoot
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.ui.MapScreen
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.clearChatRouteIfMatching
import one.meshcore.shared.navigation.clearSelectedContactIfMatching
import one.meshcore.shared.navigation.navigateToBlockedContacts
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToContactDetail
import one.meshcore.shared.navigation.navigateToContactDetailFromChat
import one.meshcore.shared.navigation.navigateToDiscovery
import one.meshcore.shared.navigation.navigateToNewChat
import one.meshcore.shared.navigation.pop
import one.meshcore.shared.navigation.reconcileSelectedChatRoute
import one.meshcore.shared.navigation.selectSetting
import one.meshcore.shared.navigation.selectTool
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.room.ui.RoomDetailPane
import one.meshcore.shared.runExceptionFallback
import one.meshcore.shared.settings.DeviceLocationPreferences
import one.meshcore.shared.settings.ui.SUPPORT_CONTACT_MAILTO
import one.meshcore.shared.settings.ui.SettingsHubDetailInputs
import one.meshcore.shared.settings.ui.SettingsHubScreen
import one.meshcore.shared.tools.ui.ToolsHubScreen

@Composable
internal fun ShellNodesTab(deps: ShellDependencies, deviceLocation: () -> LatLng?, onSheet: (ShellSheet) -> Unit) {
    val showingDiscovery by deps.coordinator.nodesShowingDiscovery.collectAsStateWithLifecycle()
    val showingBlocked by deps.coordinator.nodesShowingBlocked.collectAsStateWithLifecycle()
    val selectedContactId by deps.coordinator.selectedContactId.collectAsStateWithLifecycle()
    ContactListRoot(
        inputs = ContactListInputs(
            contactService = deps.contactService,
            radioId = deps.currentRadioId,
            connectionState = deps.connectionState,
            deviceHashSize = deps.deviceHashSize,
            onContactTap = { contactId -> deps.coordinator.navigateToContactDetail(contactId) },
            onContactRemoved = { contact -> deps.coordinator.clearSelectedContactIfMatching(contact.id.value) },
            selectedContactId = selectedContactId,
            onSendMessage = { contactId -> deps.coordinator.navigateToChat(contactId) },
            onAddContact = { onSheet(ShellSheet.AddContact) },
            onShareMyContact = { onSheet(ShellSheet.ShareMyContact) },
            discoveredNodeStore = deps.discoveredNodeStore,
            userLocation = {
                deviceLocation()?.let { UserLocation(it.latitude, it.longitude) }
            },
            selfPublicKey = deps.selfPublicKey,
            // A single app-wide DataStore backs every preference; the sort key is namespaced.
            sortOrderStore = deps.pathEditPreferences,
            locationService = deps.locationService,
            onOpenAppSettings = deps.openAppSettings,
            showingDiscovery = showingDiscovery,
            showingBlocked = showingBlocked,
            onDiscover = { deps.coordinator.navigateToDiscovery() },
            onBlockedContacts = { deps.coordinator.navigateToBlockedContacts() },
            onBackFromDiscovery = { deps.coordinator.pop(AppTab.NODES) },
            onBackFromBlocked = { deps.coordinator.pop(AppTab.NODES) },
            listInvalidations = deps.contactListInvalidations,
            maxContacts = deps.maxContacts,
        ),
    )
}

@Composable
internal fun ShellChatsTab(deps: ShellDependencies, onSheet: (ShellSheet) -> Unit) {
    val selectedChat by deps.coordinator.chatsSelectedRoute.collectAsStateWithLifecycle()
    ConversationListRoot(
        inputs = ConversationListInputs(
            store = deps.conversationListStore,
            contactService = deps.contactService,
            radioId = deps.currentRadioId,
            connectionState = deps.connectionState,
            onConversationTap = { conversation ->
                deps.coordinator.openConversation(conversation)
            },
            onStartNewChat = { contact ->
                deps.coordinator.navigateToNewChat(
                    contact.id.value,
                    displayTitle = contact.displayName,
                )
            },
            selectedRoute = selectedChat,
            onConversationDeleted = { conversation ->
                deps.coordinator.clearChatRouteIfMatching { it.matchesConversation(conversation) }
            },
            onSnapshotRefreshed = { conversations, retainingDirectContactIDs ->
                deps.coordinator.reconcileSelectedChatRoute(retainingDirectContactIDs) { route ->
                    route.refreshedPayload(conversations)
                }
            },
            onNewChannel = { onSheet(ShellSheet.ChannelOptions) },
            listInvalidations = deps.conversationListInvalidations,
            statusEvents = deps.messageStatusEvents,
            roomEvents = deps.roomServerEvents,
            channelService = deps.channelService,
            clearChannelNotifications = deps.clearChannelNotifications,
            updateBadge = deps.updateBadge,
            prewarmConversationPreviews = deps.prewarmConversationPreviews,
            prefetchConversation = deps.prefetchConversation,
        ),
    )
}

@Composable
internal fun ShellChatDetail(
    route: ChatRoute,
    deps: ShellDependencies,
    pendingScroll: PendingChatScroll,
    deviceLocation: () -> LatLng?,
    onLinkTap: (LinkTarget) -> Unit,
) {
    when (route) {
        is ChatRoute.Room -> RoomDetailPane(
            inputs = roomDetailInputs(
                sessionId = route.sessionId,
                deps = deps,
                deviceLocation = deviceLocation,
                onLinkTap = onLinkTap,
            ).copy(sessionSnapshot = route.sessionSnapshot),
        )
        else -> ChatDetailPane(
            route = route,
            binder = deps.chatThread,
            connectionState = deps.connectionState,
            pendingScroll = pendingScroll,
            onBack = { deps.coordinator.pop(AppTab.CHATS) },
            onLinkTap = onLinkTap,
            onOpenContactChat = { contactId -> deps.coordinator.navigateToChat(contactId) },
            onOpenContactInfo = { contactId ->
                deps.coordinator.navigateToContactDetailFromChat(contactId)
            },
        )
    }
}

@Composable
internal fun ShellContactDetail(
    contactId: String,
    fromDirectChat: Boolean,
    deps: ShellDependencies,
    connectionState: DeviceConnectionState,
    networkOffline: Boolean,
    onSheet: (ShellSheet) -> Unit,
) {
    ContactDetailScreen(
        inputs = contactDetailInputs(
            contactId = contactId,
            fromDirectChat = fromDirectChat,
            deps = deps,
            connectionState = connectionState,
            networkOffline = networkOffline,
            onSheet = onSheet,
        ),
    )
}

@Composable
internal fun ShellToolsTab(
    deps: ShellDependencies,
    connectionState: DeviceConnectionState,
    networkOffline: Boolean,
    deviceLocation: () -> LatLng?,
) {
    // The same per-radio recent-hops LRU the contact path editor writes, so a hop used
    // there is offered in the trace path builder.
    val recentHopsStore = remember(deps.pathEditPreferences) {
        RecentHopsStore(deps.pathEditPreferences)
    }
    ToolsHubScreen(
        selectedTool = deps.coordinator.selectedTool,
        onSelectTool = { deps.coordinator.selectTool(it) },
        inputs = toolsHubInputs(
            deps = deps,
            isRadioReady = connectionState == DeviceConnectionState.READY,
            deviceLocation = deviceLocation,
            networkOffline = networkOffline,
            recentHopsStore = recentHopsStore,
        ),
    )
}

/** A tools CLI channel read. A fault yields an empty list; a null load result stays null. */
internal suspend fun <T> loadToolsCliChannels(load: suspend () -> List<T>?): List<T>? =
    runExceptionFallback({ emptyList() }, load)

@Composable
internal fun ShellMapTab(
    deps: ShellDependencies,
    connectionState: DeviceConnectionState,
    networkOffline: Boolean,
    onSheet: (ShellSheet) -> Unit,
) {
    MapScreen(
        inputs = mapScreenInputs(
            deps = deps,
            connectionState = connectionState,
            networkOffline = networkOffline,
            onSheet = onSheet,
        ),
    )
}

@Composable
internal fun ShellSettingsTab(
    deps: ShellDependencies,
    connectionState: DeviceConnectionState,
    networkOffline: Boolean,
    onSheet: (ShellSheet) -> Unit,
) {
    val selectedSetting by deps.coordinator.selectedSetting.collectAsStateWithLifecycle()
    val offlineMapDownloadError by deps.offlineMapError.collectAsStateWithLifecycle()
    // One app-wide preference store backs every setting; these keys are namespaced by
    // Device-row id, matching the iOS DevicePreferenceStore layout.
    val deviceLocationPreferences = remember(deps.pathEditPreferences) {
        DeviceLocationPreferences(deps.pathEditPreferences)
    }
    SettingsHubScreen(
        selected = selectedSetting,
        onSelect = { deps.coordinator.selectSetting(it) },
        inputs = SettingsHubDetailInputs(
            connectionState = deps.connectionState,
            battery = deps.battery,
            batteryOcvArray = deps.batteryOcvArray,
            refreshBattery = deps.refreshBattery,
            localNodeName = deps.localNodeName,
            selfPublicKey = deps.selfPublicKey,
            deviceHashSize = deps.deviceHashSize,
            radioSnapshot = deps.radioSnapshot,
            setNodeName = deps.setNodeName,
            onShareMyContact = { onSheet(ShellSheet.ShareMyContact) },
            regionSelection = deps.regionSelection,
            applyRadioPreset = deps.applyRadioPreset,
            enableClientRepeat = deps.enableClientRepeat,
            disableClientRepeat = deps.disableClientRepeat,
            applyRepeatPreset = deps.applyRepeatPreset,
            isDeviceConnected = connectionState == DeviceConnectionState.READY,
            deviceOtherParamsSnapshot = deps.deviceOtherParamsSnapshot,
            setPathHashMode = deps.setPathHashMode,
            applyManualRadioParams = deps.applyManualRadioParams,
            locationService = deps.locationService,
            setRegionSelection = deps.setRegionSelection,
            resolveRegion = deps.resolveRegion,
            onOpenAppSettings = deps.openAppSettings,
            connectionSettingsSnapshot = deps.connectionSettingsSnapshot,
            setBlePin = deps.setBlePin,
            updateWifiEndpoint = deps.updateWifiEndpoint,
            onOpenBatteryOptimization = deps.openBatteryOptimizationSettings,
            locationSettingsSnapshot = deps.locationSettingsSnapshot,
            setAdvertLocationPolicy = deps.setAdvertLocationPolicy,
            setShareLocationPublicly = deps.setShareLocationPublicly,
            setDeviceCoordinates = deps.setDeviceCoordinates,
            connectedDeviceId = deps.connectedDeviceId,
            deviceLocationPreferences = deviceLocationPreferences,
            readDeviceGpsState = deps.readDeviceGpsState,
            setDeviceGpsEnabled = deps.setDeviceGpsEnabled,
            refreshDeviceSettings = deps.refreshDeviceSettings,
            onRequestLocationPermission = { deps.locationService?.requestPermissionIfNeeded() },
            nodesAutoAddSnapshot = deps.nodesAutoAddSnapshot,
            applyNodesAutoAdd = deps.applyNodesAutoAdd,
            setTelemetryModes = deps.setTelemetryModes,
            setMultiAcks = deps.setMultiAcks,
            staleCleanupThresholdDays = deps.staleCleanupThresholdDays(),
            onStaleCleanupThresholdChange = deps.onStaleCleanupThresholdChange,
            lastStaleCleanupSeconds = deps.lastStaleCleanupSeconds(),
            nowSeconds = deps.nowSeconds,
            deviceOcvSnapshot = deps.deviceOcvSnapshot,
            updateDeviceOcv = deps.updateDeviceOcv,
            knownRegions = deps.knownRegions,
            defaultFloodScopeName = deps.defaultFloodScopeName,
            applyDefaultFloodScope = deps.applyDefaultFloodScope,
            discoverNearbyRegions = deps.discoverNearbyRegions,
            regionDiscoverMessage = deps.regionDiscoverMessage,
            rebootDevice = deps.rebootDevice,
            onImportPrivateKey = deps.importPrivateKey,
            onForgetKeepData = deps.forgetKeepData,
            onForgetDeleteData = deps.forgetDeleteData,
            onFactoryReset = deps.factoryReset,
            unfavoritedCount = deps.unfavoritedCount,
            onRemoveUnfavorited = deps.removeUnfavorited,
            removeKnownRegion = deps.removeKnownRegion,
            addKnownRegion = deps.addKnownRegion,
            nodeConfigService = deps.nodeConfigService,
            nodeConfigImportWorkspace = deps.nodeConfigImportWorkspace,
            shareNodeConfigJson = deps.shareNodeConfigJson,
            nodeConfigExportTimestamp = deps.nodeConfigExportTimestamp,
            currentRadioId = deps.currentRadioId,
            pickNodeConfigJson = deps.pickNodeConfigJson,
            contactService = deps.contactService,
            appearancePreferences = deps.appearancePreferences,
            entitlementStore = deps.entitlementStore,
            demoModeStore = deps.demoModeStore,
            billingClient = deps.billingClient,
            entitlementReconciler = deps.entitlementReconciler,
            onContactSupport = { deps.openExternalUrl(SUPPORT_CONTACT_MAILTO) },
            onOpenUrl = deps.openExternalUrl,
            onExportDebugLogs = deps.exportDebugLogs,
            onClearDebugLogs = deps.clearDebugLogs,
            onShareDebugLogs = deps.shareDebugLogs ?: deps.shareText,
            chatDisplayPreferences = deps.chatDisplayPreferences,
            translationTargetPreferences = deps.translationTargetPreferences,
            onOpenLanguageSettings = deps.openAppLanguageSettings,
            onBlockedContactTap = { contactId -> deps.coordinator.navigateToContactDetail(contactId) },
            contactListInvalidations = deps.contactListInvalidations,
            blockedChannelSenderService = deps.blockedChannelSenderService,
            notificationPreferences = deps.notificationPreferences,
            isNotificationsAuthorized = deps.isNotificationsAuthorized,
            isNotificationPermissionDetermined = deps.isNotificationPermissionDetermined,
            onRequestNotificationPermission = deps.onRequestNotificationPermission,
            onOpenSystemNotificationSettings = deps.onOpenSystemNotificationSettings,
            notificationPermissionState = deps.permissions.state,
            mapDisplayPreferences = deps.mapDisplayPreferences,
            offlineMapPackRegistry = deps.offlineMapPackRegistry,
            startOfflineMapDownload = deps.startOfflineMapDownload,
            deleteOfflineMapPack = deps.deleteOfflineMapPack,
            pauseOfflineMapPack = deps.pauseOfflineMapPack,
            resumeOfflineMapPack = deps.resumeOfflineMapPack,
            onOfflineMapPacksChanged = deps.onOfflineMapPacksChanged,
            loadMapCameraRegion = deps.loadMapCameraRegion,
            offlineMapAvailableBytes = deps.offlineMapAvailableBytes,
            offlineMapDatabaseSizeBytes = deps.offlineMapDatabaseSizeBytes,
            isOfflineMapNetworkAvailable = { !networkOffline },
            offlineMapDownloadError = offlineMapDownloadError,
            onOfflineMapDownloadErrorShown = deps.clearOfflineMapError,
            onExportBackup = deps.exportBackup,
            backupWorkspace = deps.backupWorkspace,
            onImportBackupFile = deps.importBackupFile,
            onImportBackup = deps.importBackup,
        ),
        onConnectDevice = { onSheet(RadioConnectEntry.sheet) },
        appVersion = deps.appVersionName,
        appBuild = deps.appBuildNumber,
    )
}

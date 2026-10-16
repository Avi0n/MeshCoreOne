package one.meshcore.android

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import one.meshcore.shared.channel.RegionDiscoverUiResult
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatTimelinePrimer
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.chat.MessagePathLabels
import one.meshcore.shared.chat.ui.ChatThreadEnvironment
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.map.resolveMapIsDark
import one.meshcore.shared.notification.removeDeliveredForChannel
import one.meshcore.shared.persistence.setChannelFloodScope
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_path_direct
import one.meshcore.shared.resources.chats_message_path_flood
import one.meshcore.shared.resources.chats_message_sender_unknown
import one.meshcore.shared.settings.ChatDisplayPreferences
import one.meshcore.shared.settings.ScreenshotChatFixtures
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.theme.ThemeAccess
import one.meshcore.shared.theme.ThemeRegistry
import one.meshcore.shared.theme.resolveAppIsDark
import one.meshcore.shared.ui.ChatDestination
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal fun AppContainer.buildChatEnvironment(destination: ChatDestination): ChatThreadEnvironment =
    ChatThreadEnvironment(
        graph = chatGraphInputs(destination),
        display = chatDisplayInputs(),
        people = chatPeopleInputs(destination),
        share = chatShareInputs(destination),
        flood = chatFloodInputs(destination),
        lifecycle = chatLifecycleInputs(),
    )
internal fun AppContainer.buildChannelInfoDependencies(
    destination: ChatDestination,
): one.meshcore.shared.channel.ChannelInfoController.Dependencies? {
    if (destination.channel == null) return null
    val listStore = offlineData.graphFor(destination.conversationID.radioID).conversationListStore
    return one.meshcore.shared.channel.ChannelInfoController.Dependencies(
        channelService = { servicesForChat(destination)?.channelService },
        radioId = { destination.conversationID.radioID },
        persistNotificationLevel = { ch, level ->
            listStore.setChannelNotificationLevel(ch.id, level)
            notificationCoordinator.updateBadge()
            invalidateLists()
        },
        persistPinned = { ch, fav ->
            listStore.setChannelPinned(ch.id, fav)
            invalidateLists()
        },
        isRadioReady = {
            servicesForChat(destination) != null && connectionManager.state.value is ConnectionState.Ready
        },
        persistFloodScope = { ch, scope ->
            val owner = servicesForChat(destination)
            setChannelFloodScope(database, ch.id, scope)
            if (owner != null && servicesForChat(destination) === owner) pushChannelFloodScope(scope, owner)
        },
        deviceDefaultFloodScopeName = {
            connectedDefaultFloodScopeName.value.takeIf { servicesForChat(destination) != null }
        },
        knownRegions = { if (servicesForChat(destination) != null) connectedKnownRegions.get() else emptyList() },
        discoverNearbyRegions = {
            if (servicesForChat(destination) != null) {
                runRegionDiscoveryForChannelInfo()
            } else {
                RegionDiscoverUiResult.SendFailed
            }
        },
        regionDiscoverMessage = { result ->
            regionDiscoverStatusMessage(result)
        },
        addKnownRegion = { region -> if (servicesForChat(destination) != null) addKnownRegion(region) },
        removeKnownRegion = { region -> if (servicesForChat(destination) != null) removeKnownRegion(region) },
        clearChannelNotifications = { channelIndex, radioID ->
            notificationCoordinator.removeDeliveredForChannel(radioID, channelIndex)
            notificationCoordinator.updateBadge()
        },
    )
}

/**
 * Prefetch/arrival bake snapshot matching iOS `chatEnvInputs`: live Chat toggles, node name,
 * and localized Direct/Flood so the first open frame is not a content flash.
 */
internal suspend fun AppContainer.chatPrimeEnvInputs(): EnvInputs {
    val prefs = chatDisplayPreferences.state.value
    val nightMask = appContext.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK
    val themeId = ThemeAccess.resolveAccessibleThemeId(appearancePreferences.selectedThemeId.value, entitlementStore)
    val theme = ThemeRegistry.byId(themeId)
    val chromeIsDark = resolveAppIsDark(
        appearancePreferences.colorScheme.value,
        nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES,
        theme.forceDark,
    )
    return prefs.buildChatPrimeEnvInputs(
        currentUserName = localNodeNameFlow.value.orEmpty(),
        chromeIsDark = chromeIsDark,
        mapPreference = mapDisplayPreferences.mapColorScheme.value,
        isOffline = isNetworkOffline(),
        string = { getString(it) },
    )
}

internal suspend fun ChatDisplayPreferences.buildChatPrimeEnvInputs(
    currentUserName: String,
    chromeIsDark: Boolean,
    mapPreference: AppColorSchemePreference,
    isOffline: Boolean,
    string: suspend (StringResource) -> String,
): EnvInputs = EnvInputs(
    currentUserName = currentUserName,
    showIncomingSendTime = showIncomingSendTime,
    autoPlayGIFs = autoPlayGIFs,
    previewsEnabled = linkPreviewsEnabled,
    showMapPreviews = showMapPreviewThumbnails,
    isDark = chromeIsDark,
    mapBasemapIsDark = resolveMapIsDark(mapPreference, chromeIsDark),
    isOffline = isOffline,
    showIncomingHopCount = showIncomingHopCount,
    showIncomingRegion = showIncomingRegion,
    showIncomingHeardCount = showIncomingHeardCount,
    incomingPathLabels = if (showIncomingPath) {
        MessagePathLabels(
            direct = string(Res.string.chats_message_path_direct),
            flood = string(Res.string.chats_message_path_flood),
        )
    } else {
        null
    },
)

internal fun AppContainer.buildChatTimelinePrimer() = ChatTimelinePrimer(
    hooks = ChatTimelinePrimer.Hooks(
        registry = { currentRadioId()?.let { offlineData.threadCoordinatorRegistry(it) } },
        store = { currentRadioId()?.let { offlineData.threadChatStore(it) } },
        isConversationActive = { id ->
            when (val key = id.conversation) {
                is ChatConversationID.ConversationKey.Dm ->
                    activeConversation.activeContactID() == key.contactID
                is ChatConversationID.ConversationKey.Channel -> {
                    val open = activeConversation.activeChannel()
                    open != null && open.radioID == id.radioID && open.index == key.channelIndex
                }
            }
        },
        warmPreviews = { chatPreviewPrewarmer.prewarm(it) },
        loadContacts = { radio ->
            one.meshcore.shared.chat.pathdetail.loadContacts(contactService(), radio)
        },
        indexPage = { conversation, page ->
            connectionManager.services?.indexPrimePage(conversation, page)
        },
        envInputs = { chatPrimeEnvInputs() },
        unknownLabel = { getString(Res.string.chats_message_sender_unknown) },
    ),
)
internal fun AppContainer.startPreviewAndDemoFixtures() {
    warmClosedConversationPreviews = { chatPreviewPrewarmer.prewarm(it) }
    appScope.launch {
        combine(appState.connectionState, fixturesActive) { state, fixtures -> state.isConnected to fixtures }
            .distinctUntilChanged()
            .collectLatest { (connected, fixtures) ->
                val connectedName = if (connected) {
                    runStartupStep(
                        connectionLogger::warning,
                        LOCAL_NODE_NAME_REFRESH_FAILED,
                        fallback = { localNodeNameFlow.value },
                    ) {
                        lastConnectionStore.deviceName()
                    }
                } else {
                    null
                }
                localNodeNameFlow.value = resolveLocalNodeName(
                    connected = connected,
                    connectedName = connectedName,
                    fixturesActive = fixtures,
                    fixtureName = ScreenshotChatFixtures.NODE_NAME,
                )
            }
    }
    // Demo / screenshot mode seeds Room fixtures and exposes offline list/thread providers so chat
    // surfaces are reachable without BLE hardware. Live connection services always win when present.
    appScope.launch {
        demoFixtureActivation(demoModeStore.isEnabled, demoModeStore.isScreenshotModeEnabled).collect { wantFixtures ->
            if (wantFixtures) {
                ScreenshotChatFixtures.seed(database)
                seedDemoRxLogEntries()
                fixturesActive.value = true
            } else {
                fixturesActive.value = false
            }
        }
    }
}

internal fun resolveLocalNodeName(
    connected: Boolean,
    connectedName: String?,
    fixturesActive: Boolean,
    fixtureName: String,
): String? = if (connected) connectedName else fixtureName.takeIf { fixturesActive }

internal fun demoFixtureActivation(enabled: Flow<Boolean>, screenshot: Flow<Boolean>): Flow<Boolean> =
    combine(enabled, screenshot) { demo, capture -> demo || capture }.distinctUntilChanged()

private const val LOCAL_NODE_NAME_REFRESH_FAILED = "Local node name refresh failed"

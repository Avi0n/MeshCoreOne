package one.meshcore.android

import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.chat.liveMessageStatusEvents
import one.meshcore.shared.chat.ui.ChatThreadDisplayInputs
import one.meshcore.shared.chat.ui.ChatThreadFloodInputs
import one.meshcore.shared.chat.ui.ChatThreadGraphInputs
import one.meshcore.shared.chat.ui.ChatThreadLifecycleInputs
import one.meshcore.shared.chat.ui.ChatThreadPeopleInputs
import one.meshcore.shared.chat.ui.ChatThreadShareInputs
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.contact.ui.QrCodeEncoder
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.notification.consumeQuickReplyDraft
import one.meshcore.shared.notification.notificationKey
import one.meshcore.shared.ui.ChatDestination

internal fun AppContainer.servicesForChat(destination: ChatDestination): ServiceContainer? =
    connectionManager.services?.takeIf {
        it.radioId == destination.conversationID.radioID && !it.sendQueue.isRetired
    }

internal fun AppContainer.chatGraphInputs(destination: ChatDestination): ChatThreadGraphInputs = ChatThreadGraphInputs(
    registry = {
        offlineData.threadCoordinatorRegistry(destination.conversationID.radioID)
    },
    store = {
        offlineData.threadChatStore(destination.conversationID.radioID)
    },
    sendQueue = { servicesForChat(destination)?.sendQueue },
    messageService = { servicesForChat(destination)?.messageService },
    draftEditors = { draftEditors },
    teardownScope = { appScope },
    currentContact = { destination.contact },
    unreadCount = { destination.contact?.unreadCount ?: 0 },
    localNodeName = { localNodeNameFlow.value.takeIf { servicesForChat(destination) != null } },
    localNodeNameState = localNodeNameFlow,
    fetchDiscoveredRepeaters = { radio ->
        discoveredNodeStoreImpl.list(radio).filter {
            it.contactType == one.meshcore.shared.model.ContactType.REPEATER
        }
    },
    incomingEvents = { servicesForChat(destination)?.incomingBroadcaster?.events() ?: emptyFlow() },
    statusEvents = {
        liveMessageStatusEvents { servicesForChat(destination)?.messageService }
    },
    activeConversation = activeConversationSink,
)

internal fun AppContainer.chatDisplayInputs(): ChatThreadDisplayInputs = ChatThreadDisplayInputs(
    showIncomingSendTime = { chatDisplayPreferences.state.value.showIncomingSendTime },
    autoPlayGIFs = { chatDisplayPreferences.state.value.autoPlayGIFs },
    replyWithQuote = { chatDisplayPreferences.state.value.replyWithQuote },
    showIncomingPath = { chatDisplayPreferences.state.value.showIncomingPath },
    showIncomingHopCount = { chatDisplayPreferences.state.value.showIncomingHopCount },
    showIncomingRegion = { chatDisplayPreferences.state.value.showIncomingRegion },
    showIncomingHeardCount = { chatDisplayPreferences.state.value.showIncomingHeardCount },
    previewsEnabled = { chatDisplayPreferences.state.value.linkPreviewsEnabled },
    showMapPreviews = { chatDisplayPreferences.state.value.showMapPreviewThumbnails },
    mapSnapshotStore = { mapSnapshotStore },
    isNetworkOffline = isNetworkOffline,
    displayPreferences = chatDisplayPreferences.state,
    networkOffline = networkAvailability.offline,
    inlineImageDimensions = { inlineImageDimensions },
    linkPreviewCache = { linkPreviewCache },
    malwareFilter = { malwareDomainFilter },
)

internal fun AppContainer.chatPeopleInputs(destination: ChatDestination): ChatThreadPeopleInputs =
    ChatThreadPeopleInputs(
        contactService = { offlineData.graphFor(destination.conversationID.radioID).contactService },
        blockedChannelSenderService = { servicesForChat(destination)?.blockedChannelSenderService },
        radioId = { destination.conversationID.radioID },
        reactionService = { servicesForChat(destination)?.reactionService },
        reactionStore = { servicesForChat(destination)?.reactionStore },
        reactionSender = { servicesForChat(destination)?.reactionSender },
        recentEmojisStore = recentEmojisStore,
        frequentEmojisStore = frequentEmojisStore,
    )

internal fun AppContainer.chatShareInputs(destination: ChatDestination): ChatThreadShareInputs = ChatThreadShareInputs(
    channelInfoDependencies = { buildChannelInfoDependencies(destination) },
    qrEncode = QrCodeEncoder,
    copyToClipboard = systemCopyToClipboard,
    selfPublicKey = selfPublicKey,
    deviceHashSize = deviceHashSize,
    nodeCoordinate = {
        val snap = locationSettingsSnapshot()
        if (snap != null && snap.hasLocation) {
            LatLng(snap.latitude, snap.longitude)
        } else {
            null
        }
    },
    locationService = { locationService },
    mapDisplayPreferences = mapDisplayPreferences,
)

internal fun AppContainer.chatFloodInputs(destination: ChatDestination): ChatThreadFloodInputs = ChatThreadFloodInputs(
    floodScopeSession = { servicesForChat(destination)?.session },
    deviceDefaultFloodScopeName = {
        connectedDefaultFloodScopeName.value.takeIf { servicesForChat(destination) != null }
    },
    defaultFloodScopeNameState = connectedDefaultFloodScopeName,
    supportsUnscopedFloodSend = {
        servicesForChat(destination) != null && connectedSupportsUnscopedFloodSend.get()
    },
    floodScopePushWarning = connectionLogger::warning,
)

internal fun AppContainer.chatLifecycleInputs(): ChatThreadLifecycleInputs = ChatThreadLifecycleInputs(
    contactListInvalidations = contactListInvalidations,
    dataRestoredGeneration = offlineData.dataRestoredGeneration,
    channelSlotGenerations = channelSlotGenerations.generations,
    consumeQuickReplyDraft = { conversation, acceptsDraft ->
        notificationCoordinator.consumeQuickReplyDraft(conversation.notificationKey(), acceptsDraft)
    },
    onConversationsChanged = { invalidateLists() },
    appBackground = appBackground,
    presentSystemTranslation = systemPresentSystemTranslation,
    translationTargetPreferences = translationTargetPreferences,
)

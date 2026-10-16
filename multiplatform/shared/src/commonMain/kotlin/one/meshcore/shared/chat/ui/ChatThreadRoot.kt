package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelFloodScopeSync
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatPopulateMode
import one.meshcore.shared.chat.ChatThreadRenderState
import one.meshcore.shared.chat.ChatThreadViewModel
import one.meshcore.shared.chat.ComposerState
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.chat.MessagePathLabels
import one.meshcore.shared.chat.applyEnvInputs
import one.meshcore.shared.chat.channelAwareSenderResolver
import one.meshcore.shared.chat.clearError
import one.meshcore.shared.chat.clearErrorBanner
import one.meshcore.shared.chat.clearSendError
import one.meshcore.shared.chat.composerShowsEncryptedLock
import one.meshcore.shared.chat.consumeAnchor
import one.meshcore.shared.chat.deleteMessage
import one.meshcore.shared.chat.deliverPendingScrollRequest
import one.meshcore.shared.chat.handleChannelSenderBlocked
import one.meshcore.shared.chat.handleMessagesCleared
import one.meshcore.shared.chat.insertShared
import one.meshcore.shared.chat.launchAction
import one.meshcore.shared.chat.loadInitial
import one.meshcore.shared.chat.loadOlderMessages
import one.meshcore.shared.chat.manualPreviewFetch
import one.meshcore.shared.chat.messageByID
import one.meshcore.shared.chat.onInlineImageLoadFailed
import one.meshcore.shared.chat.onMentionSeen
import one.meshcore.shared.chat.previewImageBytes
import one.meshcore.shared.chat.refreshSenderNames
import one.meshcore.shared.chat.requestPinToBottom
import one.meshcore.shared.chat.requestPreviewFetch
import one.meshcore.shared.chat.requestScrollToBottom
import one.meshcore.shared.chat.requestScrollToDivider
import one.meshcore.shared.chat.requestScrollToMention
import one.meshcore.shared.chat.retryMessage
import one.meshcore.shared.chat.retryPreviewFetch
import one.meshcore.shared.chat.sendMessage
import one.meshcore.shared.chat.setNearBottom
import one.meshcore.shared.chat.start
import one.meshcore.shared.chat.suppressesMapPreviews
import one.meshcore.shared.chat.surfaceReactionFailure
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.chat.translation.applyComposerTranslate
import one.meshcore.shared.chat.translation.applySheetTranslate
import one.meshcore.shared.chat.translation.presentOverlayIfNeeded
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.events
import one.meshcore.shared.contact.getContactByID
import one.meshcore.shared.map.resolveMapIsDark
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_default_name
import one.meshcore.shared.resources.chats_message_path_direct
import one.meshcore.shared.resources.chats_message_path_flood
import one.meshcore.shared.resources.chats_message_sender_unknown
import one.meshcore.shared.settings.ChatDisplayPreferences
import one.meshcore.shared.theme.LocalUseDarkTheme
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

/**
 * The composition root wiring a conversation route to a [ChatThreadViewModel] and [ComposerViewModel]. It
 * resolves the shared coordinator from the registry, folds the ingest and status flows into it, loads the
 * first page, restores the draft, and flushes it on teardown. A disconnected graph (null registry/store)
 * renders the empty thread with a gated composer, mirroring the conversation-list root's disconnected path.
 */
@Composable
fun ChatThreadRoot(
    conversationID: ChatConversationID,
    title: String,
    environment: ChatThreadEnvironment,
    connectionState: StateFlow<DeviceConnectionState>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLinkTap: (LinkTarget) -> Unit = {},
    channel: ChannelDto? = null,
    contact: ContactDto? = null,
    onOpenContactChat: (String) -> Unit = {},
    onOpenContactInfo: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val connection by connectionState.collectAsStateWithLifecycle()
    val currentQueue = environment.sendQueue()
    val queueRetirement = key(currentQueue) {
        remember(currentQueue) { currentQueue?.retirementState ?: flowOf(true) }
            .collectAsStateWithLifecycle(initialValue = currentQueue?.isRetired ?: true).value
    }
    val ownsLiveRadio = !queueRetirement && currentQueue?.radioID == conversationID.radioID
    val isReady = ownsLiveRadio && threadIsReady(connection, conversationID, environment)
    val isEncrypted = composerShowsEncryptedLock(conversationID, channel)
    val floodDefaultFallback = remember { MutableStateFlow(environment.deviceDefaultFloodScopeName()) }
    val connectedDefaultFloodScopeName by stateOrFallback(
        environment.defaultFloodScopeNameState,
        floodDefaultFallback,
    ).collectAsStateWithLifecycle()
    val deviceDefaultFloodScopeName = connectedDefaultFloodScopeName.takeIf { ownsLiveRadio }

    val restoreGenerationFallback = remember { MutableStateFlow(0) }
    val restoreGeneration by stateOrFallback(
        environment.dataRestoredGeneration,
        restoreGenerationFallback,
    ).collectAsStateWithLifecycle()
    val slotGenerationFallback = remember { MutableStateFlow(emptyMap<ChatConversationID, Int>()) }
    val slotGenerations by stateOrFallback(
        environment.channelSlotGenerations,
        slotGenerationFallback,
    ).collectAsStateWithLifecycle()
    val currentSlotGeneration = slotGenerationFor(slotGenerations, conversationID)
    val slotGenerationOnAppear = remember(conversationID) { currentSlotGeneration }
    val slotWasReassigned = currentSlotGeneration > slotGenerationOnAppear
    val slotWasReassignedHolder = remember { mutableStateOf(false) }
    slotWasReassignedHolder.value = slotWasReassigned
    val registry = environment.registry()
    val store = environment.store()
    val draftEditors = environment.draftEditors()
    if (registry == null || store == null || draftEditors == null) {
        DisconnectedThread(
            title = title,
            isEncrypted = isEncrypted,
            channel = channel,
            onBack = onBack,
            modifier = modifier,
        )
        return
    }

    // Direct/Flood labels are resolved here (Compose can localize) and baked into the path row by the
    // off-thread item builder, mirroring how currentUserName is resolved live and handed down.
    val directPathLabel = stringResource(Res.string.chats_message_path_direct)
    val floodPathLabel = stringResource(Res.string.chats_message_path_flood)
    val unknownSenderLabel = stringResource(Res.string.chats_message_sender_unknown)
    val currentEnvironment by rememberUpdatedState(environment)
    val currentConnectionState by rememberUpdatedState(connectionState)
    val currentDirectPathLabel by rememberUpdatedState(directPathLabel)
    val currentFloodPathLabel by rememberUpdatedState(floodPathLabel)
    val currentUnknownSenderLabel by rememberUpdatedState(unknownSenderLabel)

    val senderTables = rememberChannelSenderTables(environment)
    val resolveSender = remember(senderTables) {
        channelAwareSenderResolver(
            tables = { senderTables.value },
            unknownLabel = { currentUnknownSenderLabel },
        )
    }
    val liveChannel = remember(channel?.id) { mutableStateOf(channel) }
    LaunchedEffect(channel) {
        liveChannel.value = channel
    }

    // A channel named "wardriving" never renders map thumbnails, whatever the global toggle says, so the
    // app cannot flood the tile API on the channel that carries a coordinate in nearly every message.
    val mapPreviewsSuppressed = suppressesMapPreviews(liveChannel.value)

    // Collect live prefs/offline so a Settings change or network edge recomposes this thread
    // (iOS `@AppStorage` + `onChange(of: envInputs)`). Fallback flows keep the collect unconditional.
    val displayPrefsFallback = remember { MutableStateFlow(ChatDisplayPreferences()) }
    val networkOfflineFallback = remember { MutableStateFlow(false) }
    val displayPrefs by stateOrFallback(
        environment.displayPreferences,
        displayPrefsFallback,
    ).collectAsStateWithLifecycle()
    val networkOffline by stateOrFallback(
        environment.networkOffline,
        networkOfflineFallback,
    ).collectAsStateWithLifecycle()
    val nodeNameFallback = remember { MutableStateFlow<String?>(null) }
    val connectedNodeName by stateOrFallback(
        environment.localNodeNameState,
        nodeNameFallback,
    ).collectAsStateWithLifecycle()
    val liveNodeName = connectedNodeName.takeIf { ownsLiveRadio }
    val isDark = LocalUseDarkTheme.current
    val mapBasemapIsDark = resolveMapIsDark(LocalMapColorSchemePreference.current, isDark)
    val currentMapBasemapIsDark by rememberUpdatedState(mapBasemapIsDark)
    val currentIsDark by rememberUpdatedState(isDark)
    val currentMapPreviewsSuppressed by rememberUpdatedState(mapPreviewsSuppressed)

    // Display toggles and the link/image preview master switch come from Chat settings (prefs).
    val envInputs = remember {
        {
            val current = currentEnvironment
            EnvInputs(
                currentUserName = chatEnvUserName(current.localNodeName()),
                showIncomingSendTime = current.showIncomingSendTime(),
                autoPlayGIFs = current.autoPlayGIFs(),
                previewsEnabled = current.previewsEnabled(),
                showMapPreviews = chatEnvShowsMapPreviews(
                    current.showMapPreviews(),
                    currentMapPreviewsSuppressed,
                ),
                isOffline = current.isNetworkOffline(),
                isDark = currentIsDark,
                mapBasemapIsDark = currentMapBasemapIsDark,
                showIncomingHopCount = current.showIncomingHopCount(),
                showIncomingRegion = current.showIncomingRegion(),
                showIncomingHeardCount = current.showIncomingHeardCount(),
                incomingPathLabels = if (current.showIncomingPath()) {
                    MessagePathLabels(direct = currentDirectPathLabel, flood = currentFloodPathLabel)
                } else {
                    null
                },
            )
        }
    }
    val pendingScroll = LocalPendingChatScroll.current
    val currentPendingScroll by rememberUpdatedState(pendingScroll)
    val indexPageForReactions = rememberReactionPageIndexer(environment, channel, contact, unknownSenderLabel)
    val chatErrorMessages = rememberChatErrorMessages()
    val currentChatErrorMessages by rememberUpdatedState(chatErrorMessages)
    val liveContact = remember(contact?.id) { mutableStateOf(contact) }
    // Registry and store identities change when a ServiceContainer is rebuilt; the process-lifetime
    // coordinator is resolved once per conversation and restore, matching iOS `ChatViewModel` `@State`.
    val viewModel = remember(conversationID, restoreGeneration) {
        ChatThreadViewModel(
            conversationID = conversationID,
            coordinator = registry.coordinator(conversationID),
            scope = scope,
            dependencies = ChatThreadViewModel.Dependencies(
                errorMessages = { currentChatErrorMessages },
                store = { currentEnvironment.store() },
                sendQueue = { currentEnvironment.sendQueue() },
                messageService = { currentEnvironment.messageService() },
                currentContact = { liveContact.value },
                unreadCount = { currentEnvironment.unreadCount() },
                isReady = { threadIsReady(currentConnectionState.value, conversationID, currentEnvironment) },
                envInputs = envInputs,
                resolveSender = resolveSender,
                incomingAvatarTable = { senderTables.value.incomingAvatars },
                activeConversation = environment.activeConversation,
                mapSnapshotStore = { currentEnvironment.mapSnapshotStore() },
                inlineImageDimensions = { currentEnvironment.inlineImageDimensions() },
                linkPreviewCache = { currentEnvironment.linkPreviewCache() },
                malwareFilter = { currentEnvironment.malwareFilter() },
                reactionService = { currentEnvironment.reactionService() },
                pendingScrollRequest = { currentPendingScroll.peek() },
                consumePendingScrollRequest = { currentPendingScroll.consume(it) },
                indexPageForReactions = indexPageForReactions,
                onConversationsChanged = { currentEnvironment.onConversationsChanged() },
            ),
        )
    }

    val composer = remember(conversationID, viewModel) {
        ComposerViewModel(
            scope = scope,
            draftEditor = draftEditors.editor(conversationID),
            maxBytes = composerByteBudget(conversationID, environment.localNodeName()),
            onSend = { text -> viewModel.launchAction { sendMessage(text) } },
        )
    }

    val renderState by viewModel.renderState.collectAsStateWithLifecycle(ChatThreadRenderState.EMPTY)
    val composerState by composer.state.collectAsStateWithLifecycle()
    val pendingRequest by pendingScroll.requests.collectAsStateWithLifecycle()
    LaunchedEffect(
        viewModel,
        renderState.completedInitialLoadGeneration,
        pendingRequest?.requestID,
        renderState.firstSnapshot,
    ) {
        viewModel.deliverPendingScrollRequest()
    }
    val currentContactService = environment.contactService()
    LaunchedEffect(contact, currentContactService, environment.contactListInvalidations) {
        contact?.let { liveContact.value = it }
        collectLiveContact(
            LiveContactLoad(
                contact = contact,
                id = contact?.id,
                current = { liveContact.value },
                load = { id -> currentContactService?.getContactByID(id) },
                invalidations = environment.contactListInvalidations,
                assign = { liveContact.value = it },
            ),
        )
    }
    LaunchedEffect(renderState.contactRefreshSignal, contact, currentContactService) {
        refreshLiveContact(
            id = contact?.id,
            signal = renderState.contactRefreshSignal,
            load = { id -> currentContactService?.getContactByID(id) },
            assign = { liveContact.value = it },
        )
    }

    // Session-scoped flood-key latch. Keyed by the live session so a reconnect (new ServiceContainer)
    // drops the previous radio's last push, matching iOS `configure` clearing `lastSetRegionScope`.
    val floodSession = environment.floodScopeSession()
    val floodScopeSync = remember(conversationID, floodSession) { ChannelFloodScopeSync() }

    // Contacts land after the first page, so repaint the baked sender names once they do.
    LaunchedEffect(viewModel, senderTables.value, unknownSenderLabel) { viewModel.refreshSenderNames() }

    // Settings or network can change while this thread stays composed (wide/split or back-stack).
    LaunchedEffect(
        viewModel,
        displayPrefs,
        networkOffline,
        isDark,
        mapBasemapIsDark,
        liveNodeName,
        directPathLabel,
        floodPathLabel,
        mapPreviewsSuppressed,
    ) {
        viewModel.applyEnvInputs()
    }
    LaunchedEffect(composer, conversationID, liveNodeName) {
        composer.updateMaxBytes(composerByteBudget(conversationID, liveNodeName))
    }

    LaunchedEffect(viewModel) {
        environment.restoreComposerDraft(composer, conversationID)
        viewModel.loadInitial()
    }
    LaunchedEffect(viewModel, currentContactService) {
        collectClearedContacts(currentContactService?.events()) { id ->
            viewModel.handleMessagesCleared(id)
        }
    }
    // iOS `ChatConversationView` collects `messageEventStream` for the screen lifetime; the
    // dispatcher is wired at Connected. Key the live-session boolean rather than the rung so
    // Connected→Syncing→Ready stays bound; the service owner still rebinds an in-place graph replacement.
    val canCollectThreadEvents = connection.canCollectThreadEvents
    val threadEventOwner = environment.messageService()
    LaunchedEffect(viewModel, canCollectThreadEvents, threadEventOwner) {
        // iOS `.task(id: servicesVersion)` re-runs `performInitialLoad` with `.refreshWindow`
        // when a new ServiceContainer appears; keep the paged window and catch up store rows.
        startThreadCollection(
            canCollect = canCollectThreadEvents,
            isLoaded = viewModel.renderState.value.isLoaded,
            start = {
                viewModel.start(safeFlow { environment.incomingEvents() }, safeFlow { environment.statusEvents() })
            },
            refresh = { viewModel.loadInitial(ChatPopulateMode.REFRESH_WINDOW) },
        )
    }
    // iOS `loadChannelMessages` → `syncFloodScope` runs at Connected (`onConnectionReady`), before Ready
    // drains the send queue. Push as soon as the session is live so inherit traffic is not keyed
    // Disabled while `deviceDefaultFloodScopeName` is still the disconnected null.
    LaunchedEffect(
        floodScopeSync,
        connection,
        deviceDefaultFloodScopeName,
        liveChannel.value,
    ) {
        pushChannelFloodScope(
            ChannelFloodPush(
                canPush = connection.canPushChannelFloodScope,
                floodScope = liveChannel.value?.floodScope,
                session = environment.floodScopeSession(),
                deviceDefaultFloodScopeName = deviceDefaultFloodScopeName,
                supportsUnscopedFloodSend = environment.supportsUnscopedFloodSend(),
            ),
        ) { session, floodScope, deviceDefault, supportsUnscoped ->
            floodScopeSync.sync(
                session = session,
                channelFloodScope = floodScope,
                deviceDefaultFloodScopeName = deviceDefault,
                supportsUnscopedFloodSend = supportsUnscoped,
                warning = environment.flood.floodScopePushWarning,
            )
        }
    }
    LaunchedEffect(slotWasReassigned) {
        leaveReassignedSlot(reassigned = slotWasReassigned, clear = { composer.setText("") }, leave = onBack)
    }
    DisposableEffect(viewModel) {
        onDispose {
            clearComposerIfReassigned(slotWasReassignedHolder.value) { composer.setText("") }
            disposeChatThread(currentEnvironment.teardownScope(), composer, viewModel)
        }
    }
    LaunchedEffect(composer, environment.appBackground) {
        environment.appBackground.collect { composer.flushDraft() }
    }

    val translationSession = rememberMessageTranslation(
        conversationID,
        environment.translationTargetPreferences?.state,
    )
    val translation = translationSession.controller
    val translationRevision = translationSession.revision
    val translationPrefs = translationSession.preferences
    LaunchedEffect(renderState.items, translation, translationPrefs.translationOffersEnabled) {
        seedChatThreadTranslations(
            offersEnabled = translationPrefs.translationOffersEnabled,
            items = renderState.items.map { item ->
                ChatTranslationSeed(
                    id = item.id,
                    outgoing = item.envelope.isOutgoing,
                    original = translationSourceText(item.content),
                )
            },
        ) { id, text ->
            translation.seedIfNeeded(id, text)
        }
    }

    val unnamedChannelTemplate = stringResource(Res.string.chats_channel_default_name)
    ChatThreadScreen(
        screenState = ChatThreadScreenState(
            thread = renderState,
            composer = composerState,
            title = threadHeaderTitle(
                liveContact.value,
                liveChannel.value,
                title,
                unnamedChannelName = { index -> formatTemplate(unnamedChannelTemplate, listOf(index)) },
            ),
            isReady = isReady,
            isEncrypted = isEncrypted,
            channel = liveChannel.value,
            contact = liveContact.value,
            isNetworkOffline = networkOffline,
        ),
        environment = environment,
        actions = ChatThreadActions(
            onTextChange = composer::setText,
            onSend = { composer.send(threadIsReady(connectionState.value, conversationID, environment)) },
            onNearTop = { viewModel.launchAction { loadOlderMessages() } },
            onNearBottomChanged = viewModel::setNearBottom,
            onScrollToBottom = viewModel::requestScrollToBottom,
            onPinToBottom = viewModel::requestPinToBottom,
            onRetry = { id -> viewModel.launchAction { retryMessage(id) } },
            onDelete = { id -> viewModel.launchAction { deleteMessage(id) } },
            onClearError = viewModel::clearError,
            onClearSendError = viewModel::clearSendError,
            onClearErrorBanner = viewModel::clearErrorBanner,
            onReactionFailed = viewModel::surfaceReactionFailure,
            onDividerTargetConsumed = viewModel::consumeAnchor,
            onBack = onBack,
            onReply = { id ->
                val message = viewModel.messageByID(id)
                applyChatReply(
                    ChatReplyRequest(
                        hasMessage = message != null,
                        isChannel = channel != null,
                        senderNodeName = message?.senderNodeName,
                        contactName = contact?.name,
                        unknownLabel = unknownSenderLabel,
                        messageText = message?.text.orEmpty(),
                        currentDraft = composer.state.value.text,
                        replyWithQuote = environment.replyWithQuote(),
                    ),
                ) { text ->
                    composer.setText(text)
                    composer.requestFocus()
                }
            },
            onFocusComposer = composer::requestFocusNow,
            onInsertShared = composer::insertShared,
            onSendAgain = { id -> viewModel.launchAction { retryMessage(id, isResend = true) } },
            onScrollToDivider = viewModel::requestScrollToDivider,
            onScrollToMention = viewModel::requestScrollToMention,
            onMentionSeen = viewModel::onMentionSeen,
        ),
        modifier = modifier,
        dependencies = ChatThreadScreenDependencies(
            onLinkTap = onLinkTap,
            fetchMessage = { id -> store.fetchMessage(id) },
            fetchRepeats = { id -> store.fetchMessageRepeats(id) },
            previews = MessagePreviewActions(
                previewImageBytes = viewModel::previewImageBytes,
                onManualPreviewFetch = viewModel::manualPreviewFetch,
                onRequestPreviewFetch = viewModel::requestPreviewFetch,
                onRetryPreviewFetch = viewModel::retryPreviewFetch,
                onInlineImageLoadFailed = viewModel::onInlineImageLoadFailed,
            ),
            onChannelDeleted = {
                // Empty the composer before teardown so flushDraft cannot re-save text onto the
                // vacated slot (iOS `onDeleteChannel` clears `composingText` then dismisses).
                composer.setText("")
                onBack()
            },
            onChannelMessagesCleared = { viewModel.launchAction { loadInitial(ChatPopulateMode.REPLACE) } },
            onChannelSenderBlocked = { viewModel.launchAction { handleChannelSenderBlocked() } },
            onChannelInfoDismiss = {
                scope.launch {
                    val service = environment.channelInfoDependencies()?.channelService?.invoke()
                    applyChannelInfoDismiss(
                        current = liveChannel.value,
                        radioId = environment.radioId(),
                        hasService = service != null,
                        load = { radio, index -> service?.getChannel(radio, index) },
                        assign = { liveChannel.value = it },
                    )
                }
            },
            onOpenContactChat = onOpenContactChat,
            onOpenContactInfo = onOpenContactInfo,
            messageAvailable = { viewModel.messageByID(it) != null },
            translationPhase = { id ->
                translationRevision
                val outgoing = renderState.items.firstOrNull { it.id == id }?.envelope?.isOutgoing == true
                translation.chrome(id, outgoing)
            },
            onTranslate = { item ->
                val original = translationSourceText(item.content)
                applyComposerTranslate(
                    showing = translation.chrome(item.id) is MessageTranslationPhase.Showing,
                    usesSystemOverlay = translationPrefs.usesSystemOverlay,
                    presentSystem = { environment.presentSystemTranslation(original) },
                    perform = {
                        scope.launch {
                            translation.performAction(item.id, original)
                                .presentOverlayIfNeeded(environment.presentSystemTranslation)
                        }
                    },
                )
            },
            onSheetTranslate = { item ->
                val original = translationSourceText(item.content)
                applySheetTranslate(
                    showing = translation.chrome(item.id) is MessageTranslationPhase.Showing,
                    presentSystem = { environment.presentSystemTranslation(original) },
                    perform = {
                        scope.launch {
                            translation.performAction(item.id, original)
                                .presentOverlayIfNeeded(environment.presentSystemTranslation)
                        }
                    },
                )
            },
        ),
    )
}

@Composable
private fun DisconnectedThread(
    title: String,
    isEncrypted: Boolean,
    channel: ChannelDto?,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    ChatThreadScreen(
        screenState = ChatThreadScreenState(
            thread = ChatThreadRenderState.EMPTY.copy(
                phase = one.meshcore.shared.chat.ChatRenderState.LoadPhase.LOADED,
            ),
            composer = ComposerState(maxBytes = ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES),
            title = title,
            isReady = false,
            isEncrypted = isEncrypted,
            channel = channel,
        ),
        actions = idleChatThreadActions(onBack),
        modifier = modifier,
    )
}

/**
 * The UTF-8 byte budget the composer enforces: a DM uses the full firmware buffer, a channel reserves the
 * `"NodeName: "` header repeaters prepend to broadcasts.
 */
private fun composerByteBudget(conversationID: ChatConversationID, localNodeName: String?): Int =
    when (conversationID.conversation) {
        is ChatConversationID.ConversationKey.Dm -> ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES
        is ChatConversationID.ConversationKey.Channel ->
            ChatConstants.maxChannelMessageLength(localNodeName)
    }

/** Subscribes lazily to a broadcaster flow when collection starts (the producer suspends to subscribe). */
private fun <T> safeFlow(producer: suspend () -> Flow<T>): Flow<T> = flow { emitAll(producer()) }

private fun threadIsReady(
    connection: DeviceConnectionState,
    conversationID: ChatConversationID,
    environment: ChatThreadEnvironment,
): Boolean {
    val queue = environment.sendQueue()
    return connection == DeviceConnectionState.READY &&
        queue != null &&
        !queue.isRetired &&
        queue.radioID == conversationID.radioID
}

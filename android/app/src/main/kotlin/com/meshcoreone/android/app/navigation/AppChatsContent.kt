// AndroidOnly: WP-407 Native conversation registration; screen scopes never create or close a radio connection.
// AndroidOnly: WP-310 Bind room body actions to the shared link dispatcher/router and radio-scoped contacts.
// AndroidOnly: WP-307 Restore pending replies on composer initialization or notification-source replacement.
package com.meshcoreone.android.app.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.feature.chats.ChatsEntry
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.ChatConversationScreen
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.rooms.RoomConversationRoute
import com.meshcoreone.android.feature.chats.rooms.RoomConversationStateHolder
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

internal fun ChatSelection.route(): ChatRoute = when (this) {
    is ChatSelection.Direct -> ChatRoute.Direct(contact)
    is ChatSelection.Channel -> ChatRoute.Channel(channel)
    is ChatSelection.Room -> ChatRoute.Room(session)
}

internal fun NavigationCoordinator.openChat(route: ChatRoute) {
    if (state.value.chatsSelectedRoute?.route() == route &&
        state.value.activeStack.last().destination is NavigationDestination.Chat
    ) return
    when (route) {
        is ChatRoute.Direct -> navigateToChat(route.contact)
        is ChatRoute.Channel -> navigateToChannel(route.channel)
        is ChatRoute.Room -> navigateToRoom(route.session)
    }
}

@Composable
internal fun AppChatsContent(
    feature: AppChatsFeature,
    destination: NavigationDestination,
    coordinator: NavigationCoordinator,
) {
    val state by coordinator.state.collectAsState()
    AppPendingChannel(feature, coordinator)
    var newChannel by remember { mutableStateOf(false) }
    if (newChannel) AppChannelsContent(feature, coordinator) { newChannel = false }
    val selected = (destination as? NavigationDestination.Chat)?.selection
    if (selected == null) {
        val radio by feature.currentRadioId.collectAsState()
        if (radio == null) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Text(stringResource(AppChatsStrings.chatsEmptyStateNoConversationsTitle))
                androidx.compose.material3.Text(stringResource(AppChatsStrings.chatsEmptyStateNoConversationsDescription))
                androidx.compose.material3.TextButton(onClick = {
                    coordinator.navigate(FeatureRoute(FeatureId.ONBOARDING))
                }) { androidx.compose.material3.Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppSettingsStrings.deviceConnect)) }
            }
            return
        }
        val list = remember(feature, radio) { feature.listFor(radio) }
        ChatsEntry(
            FeatureRoute(FeatureId.CHATS), coordinator::navigate, list,
            selectedRoute = state.chatsSelectedRoute?.route(),
            onOpenChat = coordinator::openChat,
            onClearSelection = { coordinator.back() },
            onNewChannel = { newChannel = true },
            onRoomAuthenticationRequested = { coordinator.openChat(ChatRoute.Room(it)) },
            failureText = { UiErrorMapper().message(it) },
        )
        return
    }
    key(selected) { AppChatConversation(feature, selected, coordinator, state) }
}

@Composable
private fun AppChatConversation(
    feature: AppChatsFeature,
    selected: ChatSelection,
    coordinator: NavigationCoordinator,
    state: NavigationState,
) {
        val scope = rememberCoroutineScope()
        val connection by feature.connectionState.collectAsState()
        LifecycleResumeEffect(feature, selected, connection) {
            val deactivate = feature.activate(selected)
            onPauseOrDispose { deactivate() }
        }
        if (selected is ChatSelection.Room) {
            val radio = selected.session.radioId
            val holder = remember(feature, selected) {
                RoomConversationStateHolder(selected.session, feature.room(selected.session), scope)
            }
            val list = remember(feature, radio) { feature.listFor(radio) }
            val contacts = remember(feature, radio) { mutableStateOf(emptyList<com.meshcoreone.android.core.model.ContactDTO>()) }
            LaunchedEffect(list) {
                list.reloadRequests.onStart { emit(Unit) }.collect {
                    try {
                        contacts.value = list.data.fetchContacts(radio)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        holder.reportLinkFailure(failure)
                    }
                }
            }
            val device = remember(feature, radio, scope) { feature.pathDevice(radio, scope) }
            val uriHandler = LocalUriHandler.current
            val router = remember(feature, scope) { ChatLinkRouter(feature.list.links, scope, feature.list.diagnostics) }
            val links = remember(contacts, device, radio, router, coordinator, uriHandler) {
                ConversationLinkActions(
                    contacts = { contacts.value },
                    connectedDeviceName = { device.value?.nodeName },
                    radioId = { radio },
                    route = router::route,
                    openExternal = uriHandler::openUri,
                    navigateToContact = coordinator::navigateToContactDetail,
                )
            }
            if (state.pendingRoomAuthentication?.id == selected.session.id &&
                state.pendingRoomAuthentication?.radioId == selected.session.radioId
            ) {
                val roomState by holder.state.collectAsState()
                LaunchedEffect(holder) { holder.prepareAuthentication() }
                com.meshcoreone.android.feature.chats.rooms.RoomAuthenticationSheet(
                    roomState,
                    onDismiss = coordinator::clearPendingRoomAuthentication,
                    onAuthenticate = { password, rememberPassword ->
                        scope.launch {
                            holder.authenticate(password, rememberPassword)
                            if (holder.state.value.session.isConnected) coordinator.clearPendingRoomAuthentication()
                        }
                    },
                )
            }
            val preferences = feature.content?.rendering?.collectAsState()?.value
            RoomConversationRoute(
                holder, links,
                failureText = { com.meshcoreone.android.core.ui.uiString(UiErrorMapper().message(it)) },
                replyWithQuote = preferences?.get(com.meshcoreone.android.core.datastore.AppStorageKey.replyWithQuote) == true,
                onSendDirectMessage = coordinator::navigateToChat,
            )
            return
        }
        val conversation = when (selected) {
            is ChatSelection.Direct -> TimelineConversation.Direct(selected.contact)
            is ChatSelection.Channel -> TimelineConversation.Channel(selected.channel)
            is ChatSelection.Room -> error("Room uses its own message store")
        }
        val target = when (selected) {
            is ChatSelection.Direct -> ComposerTarget.Direct(selected.contact)
            is ChatSelection.Channel -> ComposerTarget.Channel(selected.channel)
            is ChatSelection.Room -> error("Room uses its own send service")
        }
        val context = LocalContext.current
        val producer = remember(context) { LocationManagerLocationProducing(context) }
        DisposableEffect(producer) { onDispose { producer.close() } }
        var phoneLocation by remember { mutableStateOf<Coordinate?>(null) }
        val location = remember(producer) {
            object : ComposerLocationSource {
                override val isAuthorized get() =
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                override val currentCoordinate: Coordinate? get() = phoneLocation.takeIf { isAuthorized }
                override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? {
                    if (!isAuthorized) return null
                    return try {
                        withTimeout(timeoutMillis) {
                            producer.requestLocation().let { Coordinate(it.latitude, it.longitude) }.also { phoneLocation = it }
                        }
                    } catch (_: TimeoutCancellationException) {
                        feature.report("Location share timed out", null)
                        null
                    }
                }
            }
        }
        val environment = remember(feature, target, scope) { feature.environment(target, scope, location) }
        val mentionContacts by environment.mentionContacts.collectAsStateWithLifecycle()
        val composerDevice by environment.device.collectAsStateWithLifecycle()
        val bubbleLabels = rememberMessageBubbleLabels(feature.content, mentionContacts, composerDevice?.nodeName)
        val avatarTable = rememberChannelAvatars(
            if (conversation is TimelineConversation.Channel) mentionContacts else emptyList(),
        )
        val avatarCache = remember(feature) { com.meshcoreone.android.core.ui.AvatarImageCache() }
        DisposableEffect(avatarCache) { onDispose { avatarCache.clear() } }
        val timeline = remember(feature, conversation) {
            ChatViewModel(conversation, feature.timeline(conversation), scope)
        }
        var replyTo by remember(conversation.id) { mutableStateOf<java.util.UUID?>(null) }
        val sender = remember(feature, target) {
            val underlying = feature.sender(conversation.id.radioId)
            object : ComposerSendPort by underlying {
                override suspend fun createPendingDirect(
                    text: String, contact: com.meshcoreone.android.core.model.ContactDTO,
                    textType: com.meshcoreone.android.core.model.TextType, replyToId: java.util.UUID?,
                ): com.meshcoreone.android.core.model.MessageDTO {
                    val reply = replyTo
                    return underlying.createPendingDirect(text, contact, textType, reply ?: replyToId).also {
                        if (replyTo == reply) replyTo = null
                    }
                }
            }
        }
        val composer = remember(feature, target, sender) {
            ComposerStateHolder(
                target, sender, feature.drafts, environment, scope,
                ComposerTimer { delay(it) }, ComposerDiagnostics(feature::report),
            )
        }
        val notificationSource by feature.notificationSource.collectAsState()
        LaunchedEffect(composer, notificationSource) {
            feature.restoreNotificationDraft(target, composer, notificationSource)
        }
        var pickContact by remember { mutableStateOf(false) }
        var actionMessage by remember { mutableStateOf<com.meshcoreone.android.core.model.MessageDTO?>(null) }
        val emojiPicker = remember(feature, scope) { feature.content?.emojiPicker(scope) }
        if (pickContact) androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickContact = false },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(mentionContacts.size) { index ->
                        val contact = mentionContacts[index]
                        androidx.compose.material3.TextButton(onClick = {
                            composer.insertShared(ContactShare.formatShare(contact.publicKey, contact.type, contact.name))
                            pickContact = false
                        }) { androidx.compose.material3.Text(contact.displayName) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pickContact = false }) {
                    androidx.compose.material3.Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonCancel))
                }
            },
        )
        actionMessage?.let { message ->
            AppMessageActions(feature, message, conversation, timeline, composer, location, coordinator,
                onReply = { if (conversation is TimelineConversation.Direct) replyTo = it.id },
                onDismiss = { actionMessage = null })
        }
        val uriHandler = LocalUriHandler.current
        val router = remember(feature, scope) { ChatLinkRouter(feature.list.links, scope, feature.list.diagnostics) }
        val links = remember(feature, router, coordinator, uriHandler) {
            ConversationLinkActions(
                contacts = { environment.mentionContacts.value },
                connectedDeviceName = { environment.device.value?.nodeName },
                radioId = { conversation.id.radioId },
                route = router::route,
                openExternal = uriHandler::openUri,
                navigateToContact = coordinator::navigateToContactDetail,
            )
        }
        AppChatConversationScreen(
            timeline, composer, stringResource(AppChatsStrings.chatsInputPlaceholderDirectMessage), links,
            currentUserName = composerDevice?.nodeName,
            messageAccessibilityLabel = bubbleLabels,
            incomingAvatar = { message -> AppChannelAvatar(message, avatarTable, mentionContacts, avatarCache) },
            onPickContact = { pickContact = true },
            scrollTo = state.pendingScrollToMessageID,
            onScrollConsumed = { requested ->
                if (coordinator.state.value.pendingScrollToMessageID == requested) coordinator.clearPendingScrollToMessage()
            },
            report = feature::report,
            emojiPicker = emojiPicker,
            emojiStatus = {
                feature.content?.let { content ->
                    val save by content.emojiPreferences.save.collectAsStateWithLifecycle()
                    when (val current = save) {
                        is com.meshcoreone.android.app.container.EmojiPreferenceSave.Failed ->
                            androidx.compose.foundation.layout.Row {
                                androidx.compose.material3.Text(com.meshcoreone.android.core.ui.uiString(
                                    UiErrorMapper().message(current.cause),
                                ), modifier = androidx.compose.ui.Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = content.emojiPreferences::retry) {
                                    androidx.compose.material3.Text(stringResource(AppChatsStrings.chatsMessageActionRetry))
                                }
                            }
                        else -> Unit
                    }
                }
            },
            attachments = { message, outgoing ->
                feature.content?.let { AppMessageContent(it, message, feature::report, onMap = { url -> links.route(url) }) }
                androidx.compose.material3.TextButton(onClick = { actionMessage = message }) {
                    androidx.compose.material3.Text(stringResource(AppChatsStrings.chatsMessageActionDetails))
                }
            },
        )
}

// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+SendQueues.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+EventStream.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-407 native composition binds the existing chat list/timeline/composer to the single process store and live radio graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ContactLinkRequest
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.ChannelQuery
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.list.*
import com.meshcoreone.android.feature.chats.timeline.*
import com.meshcoreone.android.feature.chats.rooms.*
import com.meshcoreone.android.feature.chats.channels.*
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.notifications.NotificationService
import java.time.Clock
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.services.contacts.ChannelFloodScopeResolver

class AppChatsFeature internal constructor(private val container: AppContainer) {
    private data class PushedFloodScope(
        val session: SessionToken,
        val preference: ChannelFloodScope,
        val deviceDefault: String?,
        val supportsUnscoped: Boolean,
    )
    private val floodScopeMutex = Mutex()
    private var lastPushedScope: PushedFloodScope? = null
    private val store get() = container.processStore
    private val scope get() = container.uiScope
    private val app get() = container.appState
    private val navigation get() = container.navigation
    private val logger = Logger.getLogger("com.mc1.AppChatsFeature")
    internal val content get() = container.chatContent
    private val changes = merge(combine(
        app.servicesVersion, app.conversationsVersion, app.contactsVersion,
        app.sessionStateChangeCount, container.connectionManager.snapshot,
    ) { _, _, _, _, _ -> Unit }, container.deviceAccess.changes.map { Unit })
    val connectionState = container.connectionManager.snapshot.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, app.connectionState)
    val currentRadioId = changes.map { app.currentRadioId }
        .stateIn(scope, SharingStarted.Eagerly, app.currentRadioId)
    internal val notificationSource = app.servicesVersion.map { container.sessions.current?.notificationService }
        .stateIn(scope, SharingStarted.Eagerly, container.sessions.current?.notificationService)
    val unreadCount = merge(changes, events().map { Unit }).map {
        app.currentRadioId?.let { radio ->
            store.getTotalUnreadCounts(radio).let { Math.addExact(Math.addExact(it.contacts, it.channels), it.rooms) }
        } ?: 0L
    }.catch { failure -> report("Unread counts could not load", failure); throw failure }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    internal fun session(radio: RadioId): RadioSessionContainer {
        val current = container.sessions.current
        if (current == null || current.token.radioId != radio ||
            app.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        ) throw MessageServiceException(MessageServiceError.NotConnected)
        return current
    }

    private fun sendQueue(radio: RadioId): ChatSendQueueService {
        val current = container.sessions.current
        if (current == null || current.token.radioId != radio ||
            app.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        ) throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
        return current.chatSendQueueService
    }

    internal fun maximumChannels(): UByte = app.connectedDevice?.maxChannels ?: 0u

    internal fun pathDevice(radio: RadioId, screenScope: kotlinx.coroutines.CoroutineScope): StateFlow<DeviceDTO?> {
        fun current() = app.connectedDevice?.takeIf { it.radioId == radio }
        return changes.map { current() }.stateIn(screenScope, SharingStarted.Eagerly, current())
    }

    val drafts = object : TimelineDraftStore {
        override fun get(conversationId: ChatConversationID) = app.draftStore.draft(conversationId)
        override fun set(conversationId: ChatConversationID, text: String?) {
            if (text == null) app.draftStore.clearDraft(conversationId)
            else app.draftStore.setDraft(text, conversationId)
        }
    }

    internal fun restoreNotificationDraft(
        target: ComposerTarget,
        composer: ComposerStateHolder,
        source: NotificationService?,
    ) {
        if (target !is ComposerTarget.Direct) return
        val radio = target.contact.radioId
        val notifications = container.sessions.current?.takeIf { it.token.radioId == radio }?.notificationService
            ?: return
        if (source !== notifications) return
        val pending = notifications.consumeDraft(EntityKey(radio, target.contact.id)) ?: return
        composer.onDraftChanged(pending.ifEmpty { drafts.get(target.conversationId).orEmpty() })
    }

    val list: ChatListFeatureDependencies = listFor(null)

    fun listFor(radio: RadioId?): ChatListFeatureDependencies = object : ChatListFeatureDependencies {
        private fun key(id: UUID) = EntityKey(
            radio ?: throw MessageServiceException(MessageServiceError.NotConnected), id,
        )
        override val data = object : ChatListDataSource {
            override suspend fun fetchConversations(radioId: RadioId) = store.fetchConversations(radioId)
            override suspend fun fetchChannels(radioId: RadioId) = store.fetchChannels(radioId)
            override suspend fun fetchRemoteNodeSessions(radioId: RadioId) = store.fetchRemoteNodeSessions(radioId)
            override suspend fun fetchContacts(radioId: RadioId) = store.fetchContacts(radioId)
            override suspend fun fetchLastMessages(contactIds: List<UUID>, limit: Int) =
                store.fetchLastMessages(contactIds.map(::key).snapshot(), limit.toLong())
                    .entries.associate { it.key.id to it.value }
            override suspend fun fetchLastChannelMessages(channels: List<ChatChannelKey>, limit: Int) =
                store.fetchLastChannelMessages(
                    channels.map { ChannelQuery(it.radioId, it.channelIndex, it.id) }.snapshot(), limit.toLong(),
                ).entries.associate { it.key.id to it.value }
            override suspend fun fetchFailedSendConversationKeys(radioId: RadioId) =
                store.fetchFailedSendConversationKeys(radioId)
            override suspend fun setContactMuted(contactId: UUID, isMuted: Boolean) =
                store.setContactMuted(key(contactId), isMuted)
            override suspend fun setChannelNotificationLevel(channelId: UUID, level: NotificationLevel) =
                store.setChannelNotificationLevel(key(channelId), level)
            override suspend fun setSessionNotificationLevel(sessionId: UUID, level: NotificationLevel) =
                store.setSessionNotificationLevel(key(sessionId), level)
            override suspend fun setChannelFavorite(channelId: UUID, isFavorite: Boolean) =
                store.setChannelFavorite(key(channelId), isFavorite)
            override suspend fun setSessionFavorite(sessionId: UUID, isFavorite: Boolean) =
                store.setSessionFavorite(key(sessionId), isFavorite)
            override suspend fun deleteDirectConversation(contact: ContactDTO) {
                store.deleteMessagesForContact(EntityKey(contact.radioId, contact.id))
                store.updateContactLastMessage(EntityKey(contact.radioId, contact.id), null)
                app.refreshConversations()
            }

        }
        override val reactions = object : ChatReactionClassifier {
            override fun isDirectReaction(text: String) = ReactionParser.parseDM(text) != null
            override fun isChannelReaction(text: String) = ReactionParser.parse(text) != null
        }
        override val navigation = object : ChatListNavigation {
            override val pendingChatContact = navigationFlow { it.pendingChatContact }
            override val pendingChannel = navigationFlow { it.pendingChannel }
            override val pendingRoomSession = navigationFlow { it.pendingRoomSession }
            override val pendingRoomAuthentication = navigationFlow { it.pendingRoomAuthentication }
            override fun clearPendingChatContact() = container.navigation.clearPendingNavigation()
            override fun clearPendingChannel() = container.navigation.clearPendingChannelNavigation()
            override fun clearPendingRoomSession() = container.navigation.clearPendingRoomNavigation()
            override fun clearPendingRoomAuthentication() = container.navigation.clearPendingRoomAuthentication()
        }
        override val links = object : ChatLinkEnvironment {
            override var selectedTab: com.meshcoreone.android.core.contracts.AppTab
                get() = container.navigation.state.value.selectedTab
                set(value) = container.navigation.selectTab(value)
            override val currentRadioId get() = app.currentRadioId
            override val connectedDevicePublicKey get() = app.connectedDevice?.publicKey
            override val parser = MeshCoreLinkParser
            override fun navigateToMap(latitude: Double, longitude: Double) =
                container.navigation.navigateToMap(latitude, longitude)
            override fun navigateToContactDetail(contact: ContactDTO) =
                container.navigation.navigateToContactDetail(contact)
            override fun navigateToChannel(channel: ChannelDTO) = container.navigation.navigateToChannel(channel)
            override fun stageContactLink(link: ContactLinkResult) =
                container.navigation.stageContactLink(ContactLinkRequest(link.name, link.publicKey, link.contactType))
            override fun stageChannelLink(link: ChannelLinkResult) =
                container.navigation.stageChannelLink(ChannelLinkRequest(link.name, link.secret, link.regionScope))
            override fun stageHashtag(fullName: String) = container.navigation.stageHashtag(HashtagJoinRequest(fullName))
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = store.fetchContact(radioId, publicKey)
            override suspend fun fetchChannels(radioId: RadioId) = store.fetchChannels(radioId)
        }
        override val connectionState get() = this@AppChatsFeature.connectionState
        override val currentRadioId = MutableStateFlow(radio)
        override val reloadRequests get() = changes
        override val messageEvents: Flow<ChatListEvent> get() = events().map(::listEvent)
        override val clock: Clock = Clock.systemUTC()
        override val diagnostics = ChatListDiagnostics { operation, failure -> report(operation, failure) }
        override fun conversationServices(): ChatConversationServices? {
            val current = container.sessions.current ?: return null
            return object : ChatConversationServices {
                override suspend fun setContactFavorite(contactId: UUID, isFavorite: Boolean) =
                    session(current.token.radioId).contactService.setContactFavorite(key(contactId), isFavorite)
                override suspend fun clearChannel(radioId: RadioId, index: UByte) =
                    session(radioId).channelService.clearChannel(radioId, index)
                override suspend fun removeDeliveredNotifications(radioId: RadioId, channelIndex: UByte) =
                    current.notificationService.removeDeliveredNotifications(channelIndex, radioId)
                override suspend fun leaveRoom(session: RemoteNodeSessionDTO) =
                    this@AppChatsFeature.session(session.radioId).roomServerService.leaveRoom(
                        EntityKey(session.radioId, session.id), session.publicKey,
                    )
                override suspend fun updateBadgeCount() = current.notificationService.updateBadgeCount()
            }
        }
    }

    fun activate(selection: com.meshcoreone.android.app.navigation.ChatSelection): () -> Unit {
        val radio = when (selection) {
            is com.meshcoreone.android.app.navigation.ChatSelection.Direct -> selection.contact.radioId
            is com.meshcoreone.android.app.navigation.ChatSelection.Channel -> selection.channel.radioId
            is com.meshcoreone.android.app.navigation.ChatSelection.Room -> selection.session.radioId
        }
        val service = container.sessions.current?.takeIf { it.token.radioId == radio }?.notificationService
            ?: return {}
        when (selection) {
            is com.meshcoreone.android.app.navigation.ChatSelection.Direct -> service.setActiveConversation(contactID = selection.contact.id)
            is com.meshcoreone.android.app.navigation.ChatSelection.Channel -> service.setActiveConversation(channelIndex = selection.channel.index, channelRadioId = radio)
            is com.meshcoreone.android.app.navigation.ChatSelection.Room -> service.setActiveConversation(roomSessionID = selection.session.id)
        }
        val installed = service.activeConversation
        return { if (service.activeConversation == installed) service.setActiveConversation() }
    }

    fun actions(radio: RadioId) = object : com.meshcoreone.android.feature.chats.actions.MessageActionsDataSource {
        override suspend fun contacts(radioId: RadioId) = store.fetchContacts(radioId)
        override suspend fun discoveredNodes(radioId: RadioId) = store.fetchDiscoveredNodes(radioId)
        override suspend fun repeats(messageId: UUID) = store.fetchMessageRepeats(EntityKey(radio, messageId))
        override suspend fun reactions(messageId: UUID) = store.fetchReactions(EntityKey(radio, messageId))
        override suspend fun blockChannelSender(radioId: RadioId, senderName: String) {
            store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = senderName, radioId = radioId))
            container.sessions.current?.takeIf { it.token.radioId == radioId }?.syncCoordinator?.refreshBlockedContactsCache(radioId, store)
        }
        override suspend fun blockContacts(contactIds: Set<UUID>) {
            for (id in contactIds) {
                val contact = store.fetchContact(EntityKey(radio, id))
                    ?: throw MessageServiceException(MessageServiceError.ContactNotFound)
                store.saveContact(contact.copy(isBlocked = true))
            }
            container.sessions.current?.takeIf { it.token.radioId == radio }?.syncCoordinator?.refreshBlockedContactsCache(radio, store)
            app.bumpContactsVersion()
        }
        override suspend fun setMuted(contactId: UUID, muted: Boolean) {
            store.setContactMuted(EntityKey(radio, contactId), muted)
            app.bumpContactsVersion()
        }
    }

    suspend fun delete(message: MessageDTO) {
        store.deleteMessage(EntityKey(message.radioId, message.id))
        if (!message.isChannelMessage) {
            message.contactID?.let { store.recomputeContactLastMessageDate(EntityKey(message.radioId, it)) }
        }
        app.refreshConversations()
    }

    private suspend fun retryDirect(messageId: UUID, contact: ContactDTO, isResend: Boolean) {
        val current = container.sessions.current?.takeIf { it.token.radioId == contact.radioId }
        val envelope = DirectMessageEnvelope(messageId, contact.id, isResend)
        store.replacePendingSendForRetry(messageId, PendingSendDTO.fromEnvelope(envelope, contact.radioId))
        app.messageEventStream.send(MessageEvent.MessageResent(messageId))
        app.refreshConversations()
        val queue = current?.chatSendQueueService
            ?: throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
        queue.signalDMEnqueued(envelope)
    }

    suspend fun sendAgain(message: MessageDTO, conversation: TimelineConversation) {
        when (conversation) {
            is TimelineConversation.Direct -> retryDirect(message.id, conversation.contact, isResend = true)
            is TimelineConversation.Channel -> enqueueChannel(message.radioId, message.id, isResend = true)
        }
    }

    internal suspend fun failPending(message: MessageDTO) {
        if (store.updateMessageStatusUnlessDelivered(EntityKey(message.radioId, message.id), MessageStatus.FAILED)) {
            app.refreshConversations()
            try {
                markFailedSendSeenIfVisible(message)
            } finally {
                // The failure is already committed even if marking it seen is cancelled.
                app.refreshConversations()
                app.messageEventStream.send(MessageEvent.MessageFailed(message.id))
            }
        }
    }

    private suspend fun markFailedSendSeenIfVisible(message: MessageDTO) {
        val state = navigation.state.value
        val open = if (state.selectedTab == com.meshcoreone.android.core.contracts.AppTab.CHATS) {
            state.stacks.getValue(com.meshcoreone.android.core.contracts.AppTab.CHATS).last().destination
                as? com.meshcoreone.android.app.navigation.NavigationDestination.Chat
        } else null
        try {
            when (val selection = open?.selection) {
                is com.meshcoreone.android.app.navigation.ChatSelection.Direct ->
                    if (selection.contact.radioId == message.radioId && selection.contact.id == message.contactID) {
                        store.markFailedSendsSeen(EntityKey(message.radioId, selection.contact.id))
                    }
                is com.meshcoreone.android.app.navigation.ChatSelection.Channel ->
                    if (selection.channel.radioId == message.radioId && selection.channel.index == message.channelIndex) {
                        store.markFailedSendsSeen(message.radioId, selection.channel.index)
                    }
                else -> Unit
            }
        } catch (failure: PersistenceStoreException) {
            report("Mark visible failed send seen", failure)
        }
    }

    suspend fun react(message: MessageDTO, emoji: String, conversation: TimelineConversation) {
        val current = session(message.radioId)
        when (conversation) {
            is TimelineConversation.Direct -> current.messageService.sendDirectMessage(
                current.reactionService.buildDMReactionText(emoji, message.text, message.reactionTimestamp),
                conversation.contact,
            )
            is TimelineConversation.Channel -> current.messageService.sendChannelMessage(
                current.reactionService.buildReactionText(emoji, message.senderNodeName ?: app.connectedDevice?.nodeName
                    ?: throw MessageServiceException(MessageServiceError.NotConnected), message.text, message.reactionTimestamp),
                conversation.channel.index, conversation.channel.radioId,
            )
        }
    }

    private fun <T> navigationFlow(select: (com.meshcoreone.android.app.navigation.NavigationState) -> T) =
        navigation.state.map(select).stateIn(scope, SharingStarted.WhileSubscribed(), select(navigation.state.value))

    private fun events(): Flow<MessageEvent> = flow { emitAll(app.messageEventStream.events()) }

    fun timeline(conversation: TimelineConversation): ChatTimelineDependencies = object : ChatTimelineDependencies {
        private val radio = conversation.id.radioId
        override val content get() = this@AppChatsFeature.content
        override fun admissionIdentity() = container.sessions.current?.takeIf { it.token.radioId == radio }?.token
        override fun isCurrentAdmission(identity: SessionToken?) =
            identity != null && container.sessions.current?.token == identity &&
                app.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        override suspend fun syncChannelFloodScope(channel: ChannelDTO) = floodScopeMutex.withLock {
            val current = container.sessions.current?.takeIf {
                it.token.radioId == channel.radioId && it.token.radioId == radio &&
                    app.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            } ?: return@withLock
            val device = container.connectionManager.connectedDevice?.takeIf { it.radioId == radio }
                ?: return@withLock
            val selected = store.fetchChannel(radio, channel.index) ?: channel
            val desired = PushedFloodScope(
                current.token, selected.floodScope, device.defaultFloodScopeName, device.supportsUnscopedFloodSend,
            )
            if (desired == lastPushedScope) return@withLock
            lastPushedScope = null
            current.applyChannelFloodScope(ChannelFloodScopeResolver.resolve(
                selected.floodScope, device.defaultFloodScopeName, device.supportsUnscopedFloodSend,
            ))
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!isCurrentAdmission(current.token)) {
                throw CancellationException("Channel scope session superseded")
            }
            lastPushedScope = desired
        }
        override val draftStore get() = drafts
        override val data = object : TimelineDataSource {
            override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int) =
                when (conversation) {
                    is TimelineConversation.Direct -> store.fetchMessages(
                        EntityKey(conversation.contact.radioId, conversation.contact.id), limit.toLong(), offset.toLong(),
                    )
                    is TimelineConversation.Channel -> store.fetchMessages(
                        conversation.channel.radioId, conversation.channel.index, limit.toLong(), offset.toLong(),
                    )
                }
            override suspend fun fetchMessage(messageId: UUID) = store.fetchMessage(EntityKey(radio, messageId))
            override suspend fun unreadAnchorMessageId(conversation: TimelineConversation): UUID? =
                when (conversation) {
                    is TimelineConversation.Direct ->
                        store.newestUnreadIncomingMessage(EntityKey(radio, conversation.contact.id))?.id
                    is TimelineConversation.Channel -> null
                }
            override suspend fun clearUnread(conversation: TimelineConversation) {
                when (conversation) {
                    is TimelineConversation.Direct -> {
                        store.clearUnreadCount(EntityKey(radio, conversation.contact.id))
                        container.sessions.current?.takeIf { it.token.radioId == radio }?.notificationService
                            ?.removeDeliveredNotifications(conversation.contact.id)
                    }
                    is TimelineConversation.Channel -> {
                        store.clearChannelUnreadCount(radio, conversation.channel.index)
                        container.sessions.current?.takeIf { it.token.radioId == radio }?.notificationService
                            ?.removeDeliveredNotifications(conversation.channel.index, radio)
                    }
                }
                container.sessions.current?.notificationService?.updateBadgeCount()
                app.refreshConversations()
            }
            override suspend fun markFailedSendsSeen(conversation: TimelineConversation) = when (conversation) {
                is TimelineConversation.Direct -> store.markFailedSendsSeen(EntityKey(radio, conversation.contact.id))
                is TimelineConversation.Channel -> store.markFailedSendsSeen(radio, conversation.channel.index)
            }
        }
        override val retryService = TimelineRetryService { message, target ->
            when (target) {
                is TimelineConversation.Direct -> retryDirect(message.id, target.contact, isResend = false)
                is TimelineConversation.Channel -> enqueueChannel(radio, message.id, isResend = true)
            }
        }
        override val events: Flow<TimelineEvent> get() = this@AppChatsFeature.events().transform { event ->
            val ids = when (event) {
                is MessageEvent.DirectMessageReceived -> listOf(event.message.id)
                is MessageEvent.ChannelMessageReceived -> listOf(event.message.id)
                is MessageEvent.MessageStatusResolved -> listOf(event.messageID)
                is MessageEvent.MessageResent -> listOf(event.messageID)
                is MessageEvent.MessageFailed -> listOf(event.messageID)
                is MessageEvent.MessageRetrying -> listOf(event.messageID)
                is MessageEvent.HeardRepeatRecorded -> listOf(event.messageID)
                is MessageEvent.ReactionReceived -> listOf(event.messageID)
                is MessageEvent.MessagesRegionUpdated -> event.messageIDs
                is MessageEvent.RoutingChanged -> emptyList()
                is MessageEvent.RoomMessageReceived, is MessageEvent.RoomMessageStatusUpdated,
                is MessageEvent.RoomMessageFailed -> emptyList()
            }
            for (id in ids) {
                val message = store.fetchMessage(EntityKey(radio, id)) ?: continue
                val belongs = when (conversation) {
                    is TimelineConversation.Direct -> message.contactID == conversation.contact.id
                    is TimelineConversation.Channel -> message.channelIndex == conversation.channel.index
                }
                if (belongs) {
                    if (event is MessageEvent.MessageFailed && !message.failureSeen) {
                        markFailedSendSeenIfVisible(message)
                        app.refreshConversations()
                    }
                    emit(when (event) {
                        is MessageEvent.DirectMessageReceived, is MessageEvent.ChannelMessageReceived ->
                            TimelineEvent.MessageReceived(conversation.id, message)
                        else -> TimelineEvent.MessageChanged(conversation.id, message.id)
                    })
                }
            }
        }
        override val diagnostics = TimelineDiagnostics { operation, failure -> report(operation, failure) }
    }

    fun sender(radio: RadioId): ComposerSendPort = object : ComposerSendPort {
        override suspend fun createPendingDirect(text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?) =
            session(radio).messageService.createPendingMessage(text, contact, textType, replyToId).also {
                app.messageEventStream.send(MessageEvent.DirectMessageReceived(it, contact))
                app.refreshConversations()
            }
        override suspend fun createPendingChannel(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType) =
            session(radio).messageService.createPendingChannelMessage(text, channelIndex, radioId, textType).also {
                app.messageEventStream.send(MessageEvent.ChannelMessageReceived(it, channelIndex))
                app.refreshConversations()
            }
        override suspend fun enqueueDM(envelope: DirectMessageEnvelope) =
            sendQueue(radio).enqueueDM(envelope)
        override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) =
            sendQueue(radio).enqueueChannel(envelope)
        override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) =
            sendQueue(radio).signalDMEnqueued(envelope)
        override suspend fun recordLocalEnqueueFailure(messageId: UUID) =
            failPending(savedMessage(radio, messageId))
    }

    private suspend fun savedMessage(radio: RadioId, id: UUID): MessageDTO =
        store.fetchMessage(EntityKey(radio, id))
            ?: throw MessageServiceException(MessageServiceError.SendFailed("Saved message not found"))

    private suspend fun enqueueChannel(radio: RadioId, id: UUID, isResend: Boolean) {
        val queue = sendQueue(radio)
        val localName = app.connectedDevice?.takeIf { it.radioId == radio }?.nodeName
        val message = savedMessage(radio, id)
        val index = message.channelIndex
            ?: throw MessageServiceException(MessageServiceError.ChannelNotFound)
        queue.enqueueChannel(
            ChannelMessageEnvelope(id, index, isResend, message.text, message.timestamp, localName),
        )
    }

    fun room(room: RemoteNodeSessionDTO): RoomConversationDependencies = object : RoomConversationDependencies {
        private fun roomKey() = EntityKey(room.radioId, room.id)
        override val data = object : RoomConversationDataSource {
            override suspend fun fetchMessages(session: RemoteNodeSessionDTO) =
                store.fetchRoomMessages(EntityKey(session.radioId, session.id))
            override suspend fun refreshSession(session: RemoteNodeSessionDTO) =
                store.fetchRemoteNodeSession(EntityKey(session.radioId, session.id))
            override suspend fun contactFor(session: RemoteNodeSessionDTO) =
                store.fetchContact(session.radioId, session.publicKey)
            override suspend fun markAsRead(session: RemoteNodeSessionDTO) =
                store.resetRoomUnreadCount(EntityKey(session.radioId, session.id))
            override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) =
                store.markRoomFailedSendsSeen(EntityKey(session.radioId, session.id))
        }
        override val service = object : RoomConversationService {
            override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean) =
                session(room.radioId).roomServerService.joinRoom(
                    room.radioId, contact, password, rememberPassword, contact.outPathLength,
                )
            override suspend fun reconnect(session: RemoteNodeSessionDTO) =
                this@AppChatsFeature.session(room.radioId).roomServerService.reconnectRoom(roomKey())
            override suspend fun post(session: RemoteNodeSessionDTO, text: String) =
                this@AppChatsFeature.session(room.radioId).roomServerService.postMessage(roomKey(), text)
            override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID) =
                this@AppChatsFeature.session(room.radioId).roomServerService.retryMessage(EntityKey(room.radioId, messageId))
        }
        override val events: Flow<RoomConversationEvent> get() = merge(
            this@AppChatsFeature.events().transform<MessageEvent, RoomConversationEvent> { event ->
                val id = when (event) {
                    is MessageEvent.RoomMessageReceived -> event.message.id
                    is MessageEvent.RoomMessageStatusUpdated -> event.messageID
                    is MessageEvent.RoomMessageFailed -> event.messageID
                    else -> return@transform
                }
                val message = store.fetchRoomMessage(EntityKey(room.radioId, id)) ?: return@transform
                if (message.sessionID == room.id) {
                    emit(when (event) {
                        is MessageEvent.RoomMessageReceived -> RoomConversationEvent.MessageReceived(room.id, message)
                        is MessageEvent.RoomMessageFailed -> RoomConversationEvent.MessageFailed(room.id, message.id)
                        else -> RoomConversationEvent.MessageChanged(room.id, message.id)
                    })
                }
            },
            changes.mapNotNull {
                store.fetchRemoteNodeSession(roomKey())?.let { RoomConversationEvent.ConnectionRecovered(room.id, it) }
            }.distinctUntilChanged(),
        )
        override val diagnostics = RoomConversationDiagnostics { operation, failure -> report(operation, failure) }
        override suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) {
            container.sessions.current?.takeIf { it.token.radioId == room.radioId }?.notificationService
                ?.removeDeliveredNotificationsForRoom(session.id)
        }
        override suspend fun updateBadgeCount() {
            container.sessions.current?.notificationService?.updateBadgeCount()
        }
        override fun conversationsChanged() = app.refreshConversations()
    }

    val channels = object : ChannelSheetDependencies {
        override val data = object : ChannelSheetDataSource {
            override suspend fun fetchChannels(radioId: RadioId) = store.fetchChannels(radioId)
            override suspend fun fetchChannel(radioId: RadioId, index: UByte) = store.fetchChannel(radioId, index)
            override suspend fun setFloodScope(channel: ChannelDTO, scope: ChannelFloodScope): ChannelDTO {
                store.setChannelFloodScope(EntityKey(channel.radioId, channel.id), scope)
                return store.fetchChannel(channel.radioId, channel.index)
                    ?: throw MessageServiceException(MessageServiceError.ChannelNotFound)
            }
        }
        override val service = object : ChannelSheetService {
            override suspend fun setChannelWithSecret(radioId: RadioId, index: UByte, name: String, secret: Bytes) =
                session(radioId).channelService.setChannelWithSecret(radioId, index, name, secret)
            override suspend fun setChannel(radioId: RadioId, index: UByte, name: String, passphrase: String) =
                session(radioId).channelService.setChannel(radioId, index, name, passphrase)
            override suspend fun setupPublicChannel(radioId: RadioId) = session(radioId).channelService.setupPublicChannel(radioId)
            override fun exportChannelUri(channel: ChannelDTO) =
                ChannelService.exportChannelURI(channel.name, channel.secret, channel.floodScope)
        }
        override val diagnostics = ChannelSheetDiagnostics { operation, failure -> report(operation, failure) }
    }

    fun environment(
        target: ComposerTarget,
        screenScope: kotlinx.coroutines.CoroutineScope,
        location: ComposerLocationSource,
    ): ComposerEnvironment = object : ComposerEnvironment {
        private val radio = when (target) {
            is ComposerTarget.Direct -> target.contact.radioId
            is ComposerTarget.Channel -> target.channel.radioId
        }
        override val connectionState = container.connectionManager.snapshot.map {
            if (it.token?.radioId == radio) it.state else com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED
        }.stateIn(screenScope, SharingStarted.Eagerly, com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED)
        override val device = changes.map {
            app.connectedDevice?.takeIf { it.radioId == radio }?.let {
                ComposerDeviceInfo(it.nodeName, it.publicKey, it.latitude, it.longitude, it.hasLocation)
            }
        }.stateIn(screenScope, SharingStarted.Eagerly, null)
        override val mentionContacts = changes.map {
            val radio = when (target) {
                is ComposerTarget.Direct -> target.contact.radioId
                is ComposerTarget.Channel -> target.channel.radioId
            }
            store.fetchContacts(radio)
        }.catch { failure -> report("Load mention contacts", failure); throw failure }
            .stateIn(screenScope, SharingStarted.Eagerly, emptyList())
        override val recentSenderOrder = changes.map {
            when (target) {
                is ComposerTarget.Direct -> emptyMap()
                is ComposerTarget.Channel -> store.fetchMessages(target.channel.radioId, target.channel.index)
                    .filter { it.senderNodeName != null }
                    .groupBy { checkNotNull(it.senderNodeName) }
                    .mapValues { (_, messages) -> messages.maxOf { it.timestamp } }
            }
        }.catch { failure -> report("Load recent senders", failure); throw failure }
            .stateIn(screenScope, SharingStarted.Eagerly, emptyMap())
        override val location = location
    }

    fun report(operation: String, failure: Throwable?) {
        logger.log(Level.WARNING, operation, failure)
    }
}

private fun listEvent(event: MessageEvent): ChatListEvent = when (event) {
    is MessageEvent.DirectMessageReceived -> ChatListEvent.DIRECT_MESSAGE_RECEIVED
    is MessageEvent.ChannelMessageReceived -> ChatListEvent.CHANNEL_MESSAGE_RECEIVED
    is MessageEvent.RoomMessageReceived -> ChatListEvent.ROOM_MESSAGE_RECEIVED
    is MessageEvent.MessageStatusResolved -> ChatListEvent.MESSAGE_STATUS_RESOLVED
    is MessageEvent.MessageResent -> ChatListEvent.MESSAGE_RESENT
    is MessageEvent.MessageFailed -> ChatListEvent.MESSAGE_FAILED
    is MessageEvent.MessageRetrying -> ChatListEvent.MESSAGE_RETRYING
    is MessageEvent.HeardRepeatRecorded -> ChatListEvent.HEARD_REPEAT_RECORDED
    is MessageEvent.ReactionReceived -> ChatListEvent.REACTION_RECEIVED
    is MessageEvent.MessagesRegionUpdated -> ChatListEvent.MESSAGES_REGION_UPDATED
    is MessageEvent.RoutingChanged -> ChatListEvent.ROUTING_CHANGED
    is MessageEvent.RoomMessageStatusUpdated -> ChatListEvent.ROOM_MESSAGE_STATUS_UPDATED
    is MessageEvent.RoomMessageFailed -> ChatListEvent.ROOM_MESSAGE_FAILED
}

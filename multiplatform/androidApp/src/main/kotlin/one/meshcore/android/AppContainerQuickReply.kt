package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.meshcore.shared.channel.channelDisplayName
import one.meshcore.shared.chat.ChannelMessageEnvelope
import one.meshcore.shared.chat.DirectMessageEnvelope
import one.meshcore.shared.chat.MessageServiceException
import one.meshcore.shared.chat.createPendingChannelMessage
import one.meshcore.shared.chat.createPendingMessage
import one.meshcore.shared.chat.enqueueChannel
import one.meshcore.shared.chat.enqueueDM
import one.meshcore.shared.chat.sendChannelMessage
import one.meshcore.shared.chat.sendDirectMessage
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.contact.getContactByID
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.navigation.SendMessageShortcutOutcome
import one.meshcore.shared.navigation.ShortcutEnqueue
import one.meshcore.shared.navigation.enqueueSendMessageShortcut
import one.meshcore.shared.navigation.isSendMessageRadioReady
import one.meshcore.shared.navigation.resolveChannelTarget
import one.meshcore.shared.navigation.resolveContactTarget
import one.meshcore.shared.navigation.sendMessageShortcutSpecs
import one.meshcore.shared.notification.ConversationKey
import one.meshcore.shared.notification.ConversationTarget
import one.meshcore.shared.notification.markReadChatConversation
import one.meshcore.shared.notification.postQuickReplyFailed
import one.meshcore.shared.notification.removeDeliveredForChannel
import one.meshcore.shared.notification.saveQuickReplyDraft
import one.meshcore.shared.notification.sendQuickReplyOnRadio
import one.meshcore.shared.persistence.runRoom
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_default_name
import one.meshcore.shared.resources.tools_intent_advert_reach_flood
import one.meshcore.shared.resources.tools_intent_advert_reach_zero_hop
import one.meshcore.shared.room.markAsRead
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.getString

/**
 * Handles a notification quick-reply, mirroring the iOS `NotificationActionHandler.handleQuickReply` /
 * `handleChannelQuickReply` transaction. A send that reaches the radio clears the conversation's unread
 * state, its delivered tray rows, and the badge (the user replied, so they have read it). A send that
 * cannot happen parks the text as a draft for the next conversation open *and* posts the "Message Not
 * Sent" warning — without it the typed reply vanishes and the user believes it went out. An unresolvable
 * conversation drops the reply entirely, as the iOS contact guard does.
 * Foreground-service limits still apply — this runs from a broadcast receiver.
 */
fun AppContainer.handleNotificationQuickReply(key: ConversationKey, text: String): Job = appScope.launch {
    awaitInitialOfflineData()
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return@launch
    val conversationName = quickReplyConversationName(key) ?: return@launch
    val sent = notificationQuickReplySent { trySendQuickReply(key, trimmed) }
    if (sent) {
        clearConversationAfterQuickReply(key)
    } else {
        notificationCoordinator.saveQuickReplyDraft(key, trimmed)
        notificationCoordinator.postQuickReplyFailed(key, conversationName)
    }
}

/**
 * True when the radio accepted the reply. A cancel stops the receiver instead of parking a draft and
 * posting "Message Not Sent". Any other send failure is not sent, which is the park-and-warn path.
 */
internal suspend fun notificationQuickReplySent(send: suspend () -> Boolean): Boolean = try {
    send()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/**
 * The display name the failure warning names, read from the store rather than the live connection so a
 * reply typed while disconnected still names its conversation. Null for a DM whose contact row is gone
 * (iOS drops that reply) and for a room, which carries no reply action; a channel falls back to its
 * localized default name, as iOS `channelDisplayName` does.
 */
internal suspend fun AppContainer.quickReplyConversationName(key: ConversationKey): String? =
    when (val t = key.target()) {
        is ConversationTarget.Direct -> database.contactDao().getById(t.contactID)
            ?.let { it.nickname?.takeIf { name -> name.isNotBlank() } ?: it.name }
        is ConversationTarget.Channel -> {
            val stored = database.channelDao().getByRadioAndIndex(t.radioID, t.channelIndex)
            val unnamedTemplate = getString(Res.string.chats_channel_default_name)
            channelDisplayName(stored?.name.orEmpty(), t.channelIndex) { index ->
                formatTemplate(unnamedTemplate, listOf(index))
            }
        }
        is ConversationTarget.Room, null -> null
    }

/** The iOS post-send bookkeeping: unread cleared, tray rows for the conversation removed, badge refreshed. */
internal suspend fun AppContainer.clearConversationAfterQuickReply(key: ConversationKey) {
    when (val target = key.target()) {
        is ConversationTarget.Direct -> {
            val contact = database.contactDao().getById(target.contactID) ?: return
            val conversation = markReadChatConversation(target, contact.radioID) ?: return
            runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                offlineData.graphFor(contact.radioID).chatStore.clearUnreadCount(conversation)
            }
            notificationCoordinator.removeDeliveredForContact(target.contactID)
        }
        is ConversationTarget.Channel -> {
            val conversation = markReadChatConversation(target, contactRadioId = null) ?: return
            runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                offlineData.graphFor(target.radioID).chatStore.clearUnreadCount(conversation)
            }
            notificationCoordinator.removeDeliveredForChannel(target.radioID, target.channelIndex)
        }
        is ConversationTarget.Room, null -> return
    }
    notificationCoordinator.updateBadge()
    notificationCoordinator.notifyConversationsChanged()
}

/** Reads the live graph and partition after lookup, including in-place identity reconciliation. */
internal fun <T : Any> quickReplyReadiness(
    owner: T,
    currentOwner: () -> T?,
    state: () -> ConnectionState,
    radioId: () -> Uuid?,
): (Uuid) -> Boolean = { requestedRadio ->
    currentOwner() === owner && state() is ConnectionState.Ready && radioId() == requestedRadio
}

/**
 * @return true when the radio accepted the send (iOS `sendDirectMessage` / `sendChannelMessage`);
 * false when disconnected or the key cannot be resolved (caller parks a draft).
 */
internal suspend fun AppContainer.trySendQuickReply(key: ConversationKey, text: String): Boolean {
    val services = connectionManager.services ?: return false
    val target = key.target() ?: return false
    val messageService = services.messageService
    return sendQuickReplyOnRadio(
        target = target,
        fetchContact = { id -> services.contactService.getContactByID(id) },
        isConnectionReady = quickReplyReadiness(
            owner = services,
            currentOwner = { connectionManager.services },
            state = { connectionManager.state.value },
            radioId = { connectionManager.radioId },
        ),
        sendDirect = { contact -> messageService.sendDirectMessage(text, contact) },
        sendChannel = { index, radio ->
            messageService.sendChannelMessage(
                text,
                index,
                radio,
            )
        },
    )
}

/**
 * Shortcuts send: create a pending row and enqueue it, matching iOS `SendMessageIntent.performSend`.
 * A missing service graph or a target from another radio returns [SendMessageShortcutOutcome.MustForeground].
 */
suspend fun AppContainer.enqueueSendMessage(request: MeshCoreDeepLink.SendMessage): SendMessageShortcutOutcome {
    awaitInitialOfflineData()
    val services = connectionManager.services
    val radioID = connectionManager.radioId ?: currentRadioId()
    val ready = isSendMessageRadioReady(
        connectionManager.state.value,
        hasServices = services != null,
    )
    val contacts = radioID?.let { contactService()?.getContacts(it) }.orEmpty()
    val channels = radioID?.let { conversationListStore()?.fetchChannels(it) }.orEmpty()
    val resolvedId = when (request.kind) {
        MeshCoreDeepLink.SendMessage.Kind.CONTACT ->
            resolveContactTarget(request.targetId, contacts)?.id?.value
        MeshCoreDeepLink.SendMessage.Kind.CHANNEL ->
            resolveChannelTarget(request.targetId, channels)?.id?.value
    }
    return when {
        resolvedId == null -> SendMessageShortcutOutcome.InvalidRecipient
        services == null -> SendMessageShortcutOutcome.MustForeground
        else -> try {
            enqueueSendMessageShortcut(
                ShortcutEnqueue(
                    request = request.copy(targetId = resolvedId),
                    radioReady = ready,
                    currentRadioId = radioID,
                    fetchContact = { id ->
                        contacts.firstOrNull { it.id == id } ?: services.contactService.getContactByID(id)
                    },
                    fetchChannel = { id -> channels.firstOrNull { it.id == id } },
                    enqueueDm = { contact ->
                        val message = services.messageService.createPendingMessage(request.text, contact)
                        services.sendQueue.enqueueDM(
                            DirectMessageEnvelope(message.id, contact.id),
                        )
                    },
                    enqueueChannel = { channel ->
                        val message = services.messageService.createPendingChannelMessage(
                            request.text,
                            channel.index,
                            channel.radioID,
                        )
                        services.sendQueue.enqueueChannel(
                            ChannelMessageEnvelope(
                                messageID = message.id,
                                channelIndex = channel.index,
                                messageText = request.text,
                                messageTimestamp = message.timestamp,
                                localNodeName = localNodeNameFlow.value,
                            ),
                        )
                    },
                    localNodeName = localNodeNameFlow.value,
                ),
            )
        } catch (_: MessageServiceException.MessageTooLong) {
            SendMessageShortcutOutcome.MessageTooLong
        }
    }
}

/** Rebuilds the Assistant SEND_MESSAGE inventory from the current radio's contacts and channels. */
fun AppContainer.refreshSendMessageShortcuts() {
    sendMessageShortcutRefresh.launch {
        awaitInitialOfflineData()
        val radio = currentRadioId()
        if (radio == null) {
            sendMessageShortcuts.publish(emptyList())
            return@launch
        }
        val contacts = contactService()?.getContacts(radio).orEmpty()
        val channels = conversationListStore()?.fetchChannels(radio).orEmpty()
        sendMessageShortcuts.publish(sendMessageShortcutSpecs(contacts, channels))
    }
}

/** Publishes the launcher-hidden Send Advert reach inventory (zero-hop and flood). */
fun AppContainer.refreshSendAdvertShortcuts() {
    sendAdvertShortcutRefresh.launch {
        sendAdvertShortcuts.publish(
            zeroHopLabel = getString(Res.string.tools_intent_advert_reach_zero_hop),
            floodLabel = getString(Res.string.tools_intent_advert_reach_flood),
        )
    }
}

/**
 * Handles a notification "Mark as Read" action: clears the conversation's unread state in the data
 * layer, then refreshes the badge and conversation list, mirroring the iOS
 * `NotificationActionHandler.handleMarkAsRead` / `handleChannelMarkAsRead` / `handleRoomMarkAsRead`.
 * The tray row itself is cancelled by the receiver. A DM is keyed by the stored contact row (iOS
 * `clearUnreadCount(contactID:)`), so shade Mark as Read still works after last-radio forget.
 */
fun AppContainer.handleNotificationMarkAsRead(key: ConversationKey, messageID: Uuid? = null): Job = appScope.launch {
    awaitInitialOfflineData()
    when (val target = key.target()) {
        is ConversationTarget.Direct -> {
            val contact = database.contactDao().getById(target.contactID) ?: return@launch
            val conversation = markReadChatConversation(target, contact.radioID) ?: return@launch
            val store = offlineData.graphFor(contact.radioID).chatStore
            if (messageID != null) {
                runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                    store.markMessageAsRead(messageID)
                }
            }
            runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                store.clearUnreadCount(conversation)
            }
        }
        is ConversationTarget.Channel -> {
            val conversation = markReadChatConversation(target, contactRadioId = null) ?: return@launch
            val store = offlineData.graphFor(target.radioID).chatStore
            if (messageID != null) {
                runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                    store.markMessageAsRead(messageID)
                }
            }
            runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                store.clearUnreadCount(conversation)
            }
        }
        is ConversationTarget.Room -> {
            val roomService = offlineData.roomServerService() ?: return@launch
            if (messageID != null) {
                runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                    offlineData.chatStore()?.markMessageAsRead(messageID)
                }
            }
            runRoom(connectionLogger::warning, NOTIFICATION_READ_PERSIST_FAILED, fallback = {}) {
                roomService.markAsRead(target.sessionID)
            }
        }
        null -> return@launch
    }
    notificationCoordinator.updateBadge()
    notificationCoordinator.notifyConversationsChanged()
}

private const val NOTIFICATION_READ_PERSIST_FAILED = "Notification read state update failed"

package one.meshcore.android

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.channelDisplayName
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.connection.nodesAutoAddSnapshot
import one.meshcore.shared.contact.getContactByID
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.ChatRoute
import one.meshcore.shared.navigation.NotificationConversationOpen
import one.meshcore.shared.navigation.navigateToChannel
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.navigateToContactDetail
import one.meshcore.shared.navigation.navigateToContacts
import one.meshcore.shared.navigation.navigateToDiscovery
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.notification.ConversationKey
import one.meshcore.shared.notification.ConversationTarget
import one.meshcore.shared.notification.channelRowIdForNotification
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_default_name
import one.meshcore.shared.room.fetchSession
import one.meshcore.shared.ui.ChatDestination
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.getString

/**
 * Routes a notification body tap to the tapped conversation, the tap counterpart of the mark-read /
 * reply actions and the Kotlin mirror of the iOS `onNotificationTapped` / `onChannelNotificationTapped`
 * / `onRoomNotificationTapped` handlers. Channels resolve their volatile firmware slot back to the
 * stable row id through the live store; a disconnected room routes through the auth sheet rather than
 * opening an un-authenticated thread.
 */
internal fun AppContainer.openConversationFromNotification(open: NotificationConversationOpen) {
    notificationOpenRouting.launch {
        awaitInitialOfflineData()
        val scrollToMessageId = open.messageId
        when (val target = ConversationKey(open.conversationKey).target()) {
            is ConversationTarget.Direct ->
                coordinator.navigateToChat(
                    contactId = target.contactID.value,
                    scrollToMessageId = scrollToMessageId,
                )
            is ConversationTarget.Channel -> {
                val channels = conversationListStore()?.fetchChannels(target.radioID).orEmpty()
                val channelId = channelRowIdForNotification(channels, target.channelIndex)?.value
                    ?: database.channelDao()
                        .getByRadioAndIndex(target.radioID, target.channelIndex)?.id?.value
                    ?: return@launch
                coordinator.navigateToChannel(channelId = channelId, scrollToMessageId = scrollToMessageId)
            }
            is ConversationTarget.Room -> {
                val session = offlineData.roomServerService()?.fetchSession(target.sessionID)
                    ?: connectionManager.services?.roomServerService?.fetchSession(target.sessionID)
                    ?: database.remoteNodeSessionDao().getById(target.sessionID)?.toDto()
                coordinator.navigateToRoom(
                    sessionId = target.sessionID.value,
                    isConnected = session?.isConnected ?: false,
                )
            }
            null -> Unit
        }
    }
}

/**
 * Routes a new-contact-discovery notification tap, the Kotlin mirror of the iOS
 * `onNewContactNotificationTapped`. When the connected radio is in manual-add mode the heard node has
 * not been added, so the tap opens the Discover surface where the user reviews it; otherwise it opens
 * the contact's detail, falling back to the contacts list when the contact is missing from the store.
 */
internal fun AppContainer.openContactFromNotification(contactIdString: String) {
    notificationOpenRouting.launch {
        awaitInitialOfflineData()
        if (connectionManager.nodesAutoAddSnapshot()?.manualAddContacts == true) {
            coordinator.navigateToDiscovery()
            return@launch
        }
        val contactId = runCatching { Uuid.normalized(contactIdString) }.getOrNull() ?: return@launch
        val exists = lookupStoredContact(contactId) != null
        if (exists) coordinator.navigateToContactDetail(contactId.value) else coordinator.navigateToContacts()
    }
}

internal suspend fun AppContainer.resolveChatDestination(route: ChatRoute): ChatDestination? = when (route) {
    is ChatRoute.Direct -> directChatDestination(route)
    is ChatRoute.Channel -> channelChatDestination(route)
    is ChatRoute.Room -> null
}

private suspend fun AppContainer.directChatDestination(route: ChatRoute.Direct): ChatDestination? {
    val contact = lookupStoredContact(Uuid.normalized(route.contactId)) ?: return null
    val title = contact.nickname?.takeIf { it.isNotBlank() } ?: contact.name
    return ChatDestination(ChatConversationID.dm(contact.radioID, contact.id), title, contact)
}

private suspend fun AppContainer.channelChatDestination(route: ChatRoute.Channel): ChatDestination? {
    val channel = lookupStoredChannel(Uuid.normalized(route.channelId)) ?: return null
    val unnamedTemplate = getString(Res.string.chats_channel_default_name)
    val title = channelDisplayName(channel.name, channel.index) { index ->
        formatTemplate(unnamedTemplate, listOf(index))
    }
    return ChatDestination(
        ChatConversationID.channel(channel.radioID, channel.index),
        title,
        contact = null,
        channel = channel,
    )
}
internal suspend fun AppContainer.lookupStoredContact(id: Uuid): one.meshcore.shared.backup.ContactDto? =
    contactService()?.getContactByID(id) ?: database.contactDao().getById(id)?.toDto()
internal suspend fun AppContainer.lookupStoredChannel(id: Uuid): one.meshcore.shared.backup.ChannelDto? {
    val radio = currentRadioId()
    if (radio != null) {
        conversationListStore()?.fetchChannels(radio)?.firstOrNull { it.id == id }?.let { return it }
    }
    return database.channelDao().getById(id)?.toDto()
}

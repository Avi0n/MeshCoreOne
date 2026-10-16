package one.meshcore.shared.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ConversationListWrites
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/** Room mutations for mute, pin, clear, and leave on one radio's conversation list. */
internal class RoomConversationListWrites(private val daos: RoomConversationDaos) : ConversationListWrites {
    private val writeMutex = Mutex()

    override suspend fun setContactMuted(contactID: Uuid, isMuted: Boolean) = writeMutex.withLock {
        val current = daos.contact.getById(contactID) ?: return@withLock
        daos.contact.upsertPreservingPin(current.copy(isMuted = isMuted))
    }

    override suspend fun setChannelNotificationLevel(channelID: Uuid, level: NotificationLevel) = writeMutex.withLock {
        val current = daos.channel.getById(channelID) ?: return@withLock
        daos.channel.upsertPreservingPin(current.copy(notificationLevelRawValue = level.raw))
    }

    override suspend fun setSessionNotificationLevel(sessionID: Uuid, level: NotificationLevel) = writeMutex.withLock {
        val current = daos.remoteNodeSession.getById(sessionID) ?: return@withLock
        daos.remoteNodeSession.upsertPreservingPin(current.copy(notificationLevelRawValue = level.raw))
    }

    override suspend fun setContactPinned(contactID: Uuid, isPinned: Boolean) = writeMutex.withLock {
        check(daos.contact.setPinned(contactID, isPinned) == 1) { "Pin target missing" }
    }

    override suspend fun setChannelPinned(channelID: Uuid, isPinned: Boolean) = writeMutex.withLock {
        check(daos.channel.setPinned(channelID, isPinned) == 1) { "Pin target missing" }
    }

    override suspend fun setSessionPinned(sessionID: Uuid, isPinned: Boolean) = writeMutex.withLock {
        check(daos.remoteNodeSession.setPinned(sessionID, isPinned) == 1) { "Pin target missing" }
    }

    override suspend fun clearDirectConversation(contactID: Uuid) = writeMutex.withLock {
        val messageIds = daos.message.directMessageIdsForContact(contactID)
        daos.reaction.deleteByContact(contactID)
        if (messageIds.isNotEmpty()) {
            daos.messageRepeat.deleteByMessageIds(messageIds)
            daos.pendingSend.deleteByMessageIds(messageIds)
        }
        daos.message.deleteDirectByContact(contactID)
        daos.pendingSend.deleteByContact(contactID)
        val current = daos.contact.getById(contactID) ?: return@withLock
        daos.contact.upsertPreservingPin(current.copy(unreadCount = 0, unreadMentionCount = 0, lastMessageDate = null))
    }

    // The room logout/remove commands land with the remote-node slice; this removes the local row so the
    // conversation drops from the list.
    override suspend fun leaveRoom(session: RemoteNodeSessionDto) = writeMutex.withLock {
        val current = daos.remoteNodeSession.getById(session.id) ?: return@withLock
        daos.remoteNodeSession.upsertPreservingPin(current.copy(isConnected = false))
    }
}

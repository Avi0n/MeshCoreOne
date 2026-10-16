package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ChannelPreviewKey
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.FailedSendConversationKeys
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid

/** Resolves one writable room; every local mutation rejects accidental fixture actions. */
@Suppress("TooManyFunctions") // The store contract includes eight independent write operations.
internal class KeyboardRoomStore : ConversationListStore {
    var session = RemoteNodeSessionDto(
        id = Uuid.normalized("D0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("D0000000-0000-0000-0000-000000000002"),
        publicKey = ByteArray(32),
        name = "Room keyboard fixture",
        role = RemoteNodeRole.ROOM_SERVER,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = true,
        permissionLevel = RoomPermissionLevel.READ_WRITE,
        unreadCount = 0,
        notificationLevel = NotificationLevel.ALL,
        neighborCount = 0,
        lastSyncTimestamp = 0,
    )

    override suspend fun fetchRooms(radioID: Uuid): List<RemoteNodeSessionDto> = listOf(session).filter {
        it.radioID ==
            radioID
    }

    override suspend fun fetchConversationContacts(radioID: Uuid): List<ContactDto> = emptyList()

    override suspend fun fetchContactIDs(radioID: Uuid): Set<Uuid> = emptySet()

    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = emptyList()

    override suspend fun fetchFailedSendConversationKeys(radioID: Uuid) = FailedSendConversationKeys()

    override suspend fun fetchLastContactMessages(contactIDs: List<Uuid>, limit: Int): Map<Uuid, List<MessageDto>> =
        emptyMap()

    override suspend fun fetchLastChannelMessages(
        channels: List<ChannelPreviewKey>,
        limit: Int,
    ): Map<Uuid, List<MessageDto>> = emptyMap()

    override suspend fun setContactMuted(contactID: Uuid, isMuted: Boolean) = error("Unexpected write")

    override suspend fun setChannelNotificationLevel(channelID: Uuid, level: NotificationLevel) =
        error("Unexpected write")

    override suspend fun setSessionNotificationLevel(sessionID: Uuid, level: NotificationLevel) =
        error("Unexpected write")

    override suspend fun setContactPinned(contactID: Uuid, isPinned: Boolean) = error("Unexpected write")

    override suspend fun setChannelPinned(channelID: Uuid, isPinned: Boolean) = error("Unexpected write")

    override suspend fun setSessionPinned(sessionID: Uuid, isPinned: Boolean) = error("Unexpected write")

    override suspend fun clearDirectConversation(contactID: Uuid) = error("Unexpected write")

    override suspend fun leaveRoom(session: RemoteNodeSessionDto) = error("Unexpected write")
}

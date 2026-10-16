package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/**
 * An in-memory [ConversationListStore] for the conversation-list view-model tests. Fetches return the
 * mutable buffers set by the test; the mutation methods record their calls and can be armed to throw so
 * the optimistic-write rollback and delete-failure paths are exercised. Preview maps are keyed by row id.
 */
class FakeConversationListStore : ConversationListStore {
    var contacts: List<ContactDto> = emptyList()
    var contactIDs: Set<Uuid> = emptySet()
    var fetchContactIDsFailure: Throwable? = null
    var beforeContactIDsFetch: suspend () -> Unit = {}
    var channels: List<ChannelDto> = emptyList()
    var rooms: List<RemoteNodeSessionDto> = emptyList()
    var contactPreviews: Map<Uuid, List<MessageDto>> = emptyMap()
    var channelPreviews: Map<Uuid, List<MessageDto>> = emptyMap()
    var failedSendKeys: FailedSendConversationKeys = FailedSendConversationKeys()

    /** Ids for which the next matching write/delete throws, simulating a device rejection. */
    val failingIds: MutableSet<Uuid> = mutableSetOf()

    /** When set, [clearDirectConversation] throws this before the armed-id check. */
    var clearDirectFailure: Throwable? = null

    /** When set, a contact, channel, or room write throws this before the armed-id check. */
    var writeFailure: Throwable? = null
    var beforePinWrite: suspend () -> Unit = {}

    /** When set, [fetchConversationContacts] throws this instead of returning [contacts]. */
    var fetchContactsFailure: Throwable? = null

    /** When set, [fetchChannels] throws this instead of returning [channels]. */
    var fetchChannelsFailure: Throwable? = null

    /** When set, [fetchRooms] throws this instead of returning [rooms]. */
    var fetchRoomsFailure: Throwable? = null

    /** When set, [fetchFailedSendConversationKeys] throws this instead of returning [failedSendKeys]. */
    var fetchFailedSendFailure: Throwable? = null

    /** When set, [fetchLastContactMessages] throws this instead of returning previews. */
    var fetchContactPreviewFailure: Throwable? = null

    /** When set, [fetchLastChannelMessages] throws this instead of returning previews. */
    var fetchChannelPreviewFailure: Throwable? = null

    val notificationLevelWrites: MutableList<Pair<Uuid, NotificationLevel?>> = mutableListOf()
    val pinnedWrites: MutableList<Pair<Uuid, Boolean>> = mutableListOf()
    val clearedDirects: MutableList<Uuid> = mutableListOf()
    val leftRooms: MutableList<Uuid> = mutableListOf()

    override suspend fun fetchConversationContacts(radioID: Uuid): List<ContactDto> {
        fetchContactsFailure?.let { throw it }
        return contacts
    }

    override suspend fun fetchContactIDs(radioID: Uuid): Set<Uuid> {
        beforeContactIDsFetch()
        fetchContactIDsFailure?.let { throw it }
        return contactIDs
    }

    override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> {
        fetchChannelsFailure?.let { throw it }
        return channels
    }

    override suspend fun fetchRooms(radioID: Uuid): List<RemoteNodeSessionDto> {
        fetchRoomsFailure?.let { throw it }
        return rooms
    }

    override suspend fun fetchFailedSendConversationKeys(radioID: Uuid): FailedSendConversationKeys {
        fetchFailedSendFailure?.let { throw it }
        return failedSendKeys
    }

    override suspend fun fetchLastContactMessages(contactIDs: List<Uuid>, limit: Int): Map<Uuid, List<MessageDto>> {
        fetchContactPreviewFailure?.let { throw it }
        return contactPreviews.filterKeys { it in contactIDs }
    }

    override suspend fun fetchLastChannelMessages(
        channels: List<ChannelPreviewKey>,
        limit: Int,
    ): Map<Uuid, List<MessageDto>> {
        fetchChannelPreviewFailure?.let { throw it }
        return channelPreviews.filterKeys { id -> channels.any { it.id == id } }
    }

    override suspend fun setContactMuted(contactID: Uuid, isMuted: Boolean) {
        failIfArmed(contactID)
        notificationLevelWrites += contactID to if (isMuted) NotificationLevel.MUTED else NotificationLevel.ALL
    }

    override suspend fun setChannelNotificationLevel(channelID: Uuid, level: NotificationLevel) {
        failIfArmed(channelID)
        notificationLevelWrites += channelID to level
    }

    override suspend fun setSessionNotificationLevel(sessionID: Uuid, level: NotificationLevel) {
        failIfArmed(sessionID)
        notificationLevelWrites += sessionID to level
    }

    override suspend fun setContactPinned(contactID: Uuid, isPinned: Boolean) {
        beforePinWrite()
        failIfArmed(contactID)
        pinnedWrites += contactID to isPinned
    }

    override suspend fun setChannelPinned(channelID: Uuid, isPinned: Boolean) {
        beforePinWrite()
        failIfArmed(channelID)
        pinnedWrites += channelID to isPinned
    }

    override suspend fun setSessionPinned(sessionID: Uuid, isPinned: Boolean) {
        beforePinWrite()
        failIfArmed(sessionID)
        pinnedWrites += sessionID to isPinned
    }

    override suspend fun clearDirectConversation(contactID: Uuid) {
        clearDirectFailure?.let { throw it }
        failIfArmed(contactID)
        clearedDirects += contactID
        // A cleared DM stops being a conversation, so a later fetch no longer returns it.
        contacts = contacts.filterNot { it.id == contactID }
    }

    override suspend fun leaveRoom(session: RemoteNodeSessionDto) {
        failIfArmed(session.id)
        leftRooms += session.id
        rooms = rooms.filterNot { it.id == session.id }
    }

    private fun failIfArmed(id: Uuid) {
        writeFailure?.let { throw it }
        if (id in failingIds) error("armed failure for $id")
    }
}

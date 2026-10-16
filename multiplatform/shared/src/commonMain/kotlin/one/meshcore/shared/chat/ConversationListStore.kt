package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid

/** Conversation-list reads and local writes for the live or offline radio partition. */
interface ConversationListStore : ConversationListWrites {
    /** Contacts that are conversations for [radioID] (the view model excludes repeaters, blocked, masked). */
    suspend fun fetchConversationContacts(radioID: Uuid): List<ContactDto>

    /** All persisted contact IDs, including contacts without a conversation row. */
    suspend fun fetchContactIDs(radioID: Uuid): Set<Uuid>

    /** Channels for [radioID] (the view model keeps only named-or-secret ones). */
    suspend fun fetchChannels(radioID: Uuid): List<ChannelDto>

    /** Room sessions for [radioID]. */
    suspend fun fetchRooms(radioID: Uuid): List<RemoteNodeSessionDto>

    /** Conversation ids with at least one outgoing failed send for [radioID]. */
    suspend fun fetchFailedSendConversationKeys(radioID: Uuid): FailedSendConversationKeys

    /**
     * Batch-fetches the most recent [limit] messages per contact, keyed by contact id, each list in
     * ascending time order (so the newest matching preview is the last element). One actor hop for all.
     */
    suspend fun fetchLastContactMessages(contactIDs: List<Uuid>, limit: Int): Map<Uuid, List<MessageDto>>

    /** Batch-fetches the most recent [limit] messages per channel, keyed by channel id, ascending time. */
    suspend fun fetchLastChannelMessages(channels: List<ChannelPreviewKey>, limit: Int): Map<Uuid, List<MessageDto>>
}

/** The identity a channel preview fetch keys on: the row id plus the radio + slot the messages live under. */
data class ChannelPreviewKey(val id: Uuid, val radioID: Uuid, val channelIndex: Int)

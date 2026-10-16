package one.meshcore.shared.persistence

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid

internal enum class RoomChatContactMetadataNativeCase {
    LAST_MESSAGE,
    UNREAD,
    MENTION,
    HEARD,
    CLEAR_BOTH,
    CLEAR_UNREAD,
    RECOMPUTE,
    ;

    suspend fun invoke(store: CapturedChatContactMetadataNativeStores, radioID: Uuid, contactID: Uuid, heard: Long) {
        val dm = ChatConversationID.dm(radioID, contactID)
        when (this) {
            LAST_MESSAGE -> store.messages.updateContactLastMessage(contactID, ASSIGNED_DATE)
            UNREAD -> store.ingest.incrementContactUnread(contactID)
            MENTION -> store.ingest.incrementContactUnreadMention(contactID)
            HEARD -> store.ingest.touchContactHeard(contactID, heard)
            CLEAR_BOTH -> store.thread.clearUnread(dm)
            CLEAR_UNREAD -> store.thread.clearUnreadCount(dm)
            RECOMPUTE -> store.thread.recomputeContactLastMessageDate(contactID)
        }
    }

    fun expected(row: ContactEntity, historyDate: Double, heard: Long): ContactEntity = when (this) {
        LAST_MESSAGE -> row.copy(lastMessageDate = ASSIGNED_DATE)
        UNREAD -> row.copy(unreadCount = row.unreadCount + 1)
        MENTION -> row.copy(unreadMentionCount = row.unreadMentionCount + 1)
        HEARD -> row.copy(lastHeardTimestamp = heard)
        CLEAR_BOTH -> row.copy(unreadCount = 0, unreadMentionCount = 0)
        CLEAR_UNREAD -> row.copy(unreadCount = 0)
        RECOMPUTE -> row.copy(lastMessageDate = historyDate)
    }

    fun message(id: Uuid, radioID: Uuid, contactID: Uuid, date: Double, channelIndex: Int? = null) = MessageDto(
        id = id, radioID = radioID, contactID = contactID, channelIndex = channelIndex,
        text = "Synthetic native history", timestamp = BASE.toLong(), createdAt = BASE, sortDateRaw = date,
        direction = MessageDirection.OUTGOING, status = MessageStatus.SENT, textType = TextType.PLAIN,
        pathLength = 0, isRead = false, heardRepeats = 0, sendCount = 1, retryAttempt = 0,
        maxRetryAttempts = 0, linkPreviewFetched = false, containsSelfMention = false,
        mentionSeen = false, timestampCorrected = false,
    )

    fun discovered(contact: ContactEntity, stamp: Long): DiscoveredNodeRecord = DiscoveredNodeRecord(
        id = DISCOVER_ID, radioID = contact.radioID, publicKey = contact.publicKey.copyOf(), name = contact.name,
        typeRawValue = contact.typeRawValue, lastHeardEpochSeconds = stamp,
        lastAdvertTimestamp = contact.lastAdvertTimestamp, latitude = contact.latitude, longitude = contact.longitude,
        outPathLength = contact.outPathLength, outPath = contact.outPath.copyOf(),
        inboundHopCount = 2, inboundHopAdvertTimestamp = contact.lastAdvertTimestamp,
    )

    enum class HeardMode(val initial: Long?, val incoming: Long, val expected: Long, val discoverSeed: Long) {
        NEW(OLD_HEARD, NEW_HEARD, NEW_HEARD, OLD_HEARD),
        OLDER(OLD_HEARD, OLD_HEARD - 10, OLD_HEARD, OLD_HEARD - 10),
        EQUAL(OLD_HEARD, OLD_HEARD, OLD_HEARD, OLD_HEARD - 10),
        NULL_ZERO(null, 0L, 0L, -10L),
    }

    companion object {
        const val BASE = 1_700_000_000.0
        const val LOCAL_HISTORY_DATE = BASE + 100.0
        const val OTHER_CONTACT_DATE = BASE + 900.0
        const val CHANNEL_DATE = BASE + 1_100.0
        const val OTHER_RADIO_DATE = BASE + 1_300.0
        private val DISCOVER_ID = Uuid.normalized("88888888-8888-8888-8888-888888888888")
        const val SEED_DATE = 1_700_000_200.0
        const val ASSIGNED_DATE = 1_700_000_400.0
        const val HISTORY_DATE = 1_700_000_600.0
        private const val OLD_HEARD = 1_700_000_000L
        private const val NEW_HEARD = 1_700_000_500L
    }
}

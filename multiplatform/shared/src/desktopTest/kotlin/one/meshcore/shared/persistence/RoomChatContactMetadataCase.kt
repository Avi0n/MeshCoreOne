package one.meshcore.shared.persistence

import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid

internal enum class RoomChatContactMetadataCase {
    LAST_MESSAGE,
    UNREAD,
    MENTION,
    HEARD,
    CLEAR_BOTH,
    CLEAR_UNREAD,
    RECOMPUTE,
    ;

    suspend fun invoke(store: CapturedChatContactMetadataStores, contactID: Uuid, heard: Long) {
        val dm = ChatConversationID.dm(ChatFixtures.RADIO, contactID)
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

    enum class HeardMode(val initial: Long?, val incoming: Long, val expected: Long, val discoverSeed: Long) {
        NEW(OLD_HEARD, NEW_HEARD, NEW_HEARD, OLD_HEARD),
        OLDER(OLD_HEARD, OLD_HEARD - 10, OLD_HEARD, OLD_HEARD - 10),
        EQUAL(OLD_HEARD, OLD_HEARD, OLD_HEARD, OLD_HEARD - 10),
        NULL_ZERO(null, 0L, 0L, -10L),
    }

    companion object {
        const val SEED_DATE = 1_700_000_200.0
        const val ASSIGNED_DATE = 1_700_000_400.0
        const val HISTORY_DATE = 1_700_000_600.0
        private const val OLD_HEARD = 1_700_000_000L
        private const val NEW_HEARD = 1_700_000_500L
    }
}

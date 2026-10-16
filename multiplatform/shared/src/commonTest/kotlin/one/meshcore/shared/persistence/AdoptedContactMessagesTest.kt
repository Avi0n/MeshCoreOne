package one.meshcore.shared.persistence

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class AdoptedContactMessagesTest {
    @Test
    fun newerOrphanDateReplacesAnOlderOrMissingDate() {
        val older = contact(lastMessageDate = 10.0)
        assertEquals(
            12.0,
            older.withAdoptedMessages(newest = 12.0, unreadBump = null, mentionBump = null).lastMessageDate,
        )
        val missing = contact(lastMessageDate = null)
        assertEquals(
            4.0,
            missing.withAdoptedMessages(newest = 4.0, unreadBump = null, mentionBump = null).lastMessageDate,
        )
        val newer = contact(lastMessageDate = 20.0)
        assertEquals(
            20.0,
            newer.withAdoptedMessages(newest = 12.0, unreadBump = null, mentionBump = null).lastMessageDate,
        )
    }

    @Test
    fun unreadAndMentionBumpsApplyOnlyWhenTheContactIsOpen() {
        val open = contact(unreadCount = 1, unreadMentionCount = 2)
        val updated = open.withAdoptedMessages(newest = null, unreadBump = 3, mentionBump = 1)
        assertEquals(4, updated.unreadCount)
        assertEquals(3, updated.unreadMentionCount)

        val blocked = contact(blocked = true, unreadCount = 1, unreadMentionCount = 2, lastMessageDate = 5.0)
        val blockedUpdated = blocked.withAdoptedMessages(newest = 9.0, unreadBump = 3, mentionBump = 1)
        assertEquals(9.0, blockedUpdated.lastMessageDate)
        assertEquals(1, blockedUpdated.unreadCount)
        assertEquals(2, blockedUpdated.unreadMentionCount)
    }

    @Test
    fun aZeroOrMissingBumpLeavesTheRowAsItWas() {
        val row = contact(unreadCount = 1, unreadMentionCount = 2, lastMessageDate = 5.0)
        assertSame(row, row.withAdoptedMessages(newest = null, unreadBump = 0, mentionBump = null))
        assertSame(row, row.withAdoptedMessages(newest = 5.0, unreadBump = null, mentionBump = -1))
    }
}

private fun contact(
    blocked: Boolean = false,
    lastMessageDate: Double? = null,
    unreadCount: Int = 0,
    unreadMentionCount: Int = 0,
) = ContactEntity(
    id = Uuid.normalized("00000000-0000-0000-0000-0000000000AA"),
    radioID = Uuid.normalized("00000000-0000-0000-0000-0000000000BB"),
    publicKey = ByteArray(1),
    name = "Ada",
    typeRawValue = 0,
    flags = 0,
    outPathLength = 0,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0,
    isBlocked = blocked,
    isMuted = false,
    isFavorite = false,
    unreadCount = unreadCount,
    unreadMentionCount = unreadMentionCount,
    lastMessageDate = lastMessageDate,
)

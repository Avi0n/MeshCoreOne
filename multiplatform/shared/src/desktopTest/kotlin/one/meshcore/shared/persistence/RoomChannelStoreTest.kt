package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RoomChannelStore], the production adapter `ChannelService` drives. Locks the slot-keyed
 * upsert's local-only-field preservation, the batch prune/delete rules, and the message-clear cascade
 * against a real in-memory Room database — the behaviors `InMemoryChannelStore` is the reference for.
 */
class RoomChannelStoreTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var store: RoomChannelStore

    private val radioID = ChatFixtures.RADIO

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("room-channel-store", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        store = RoomChannelStore(db, newId = ChatFixtures::freshUuid)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    private fun channelInfo(index: Int, name: String, secretByte: Int) =
        ChannelInfo(index = index, name = name, secret = ByteArray(SECRET_BYTES) { secretByte.toByte() })

    @Test
    fun upsertOnAnOccupiedSlotRewritesOnlyNameAndSecretAndPreservesLocalFields() = runBlocking {
        val id = store.saveChannel(radioID, channelInfo(index = 1, name = "Alpha", secretByte = 0x11))
        // Stamp every local-only field the wire frame does not carry, straight onto the persisted row.
        db.channelDao().upsert(
            db.channelDao().getByRadioAndIndex(radioID, 1)!!.copy(
                unreadCount = 4,
                unreadMentionCount = 2,
                notificationLevelRawValue = NotificationLevel.MENTIONS_ONLY.raw,
                isPinned = true,
                lastMessageDate = 123.0,
            ),
        )

        val sameId = store.saveChannel(radioID, channelInfo(index = 1, name = "Renamed", secretByte = 0x11))
        val row = store.fetchChannel(radioID, 1)!!

        assertEquals(id, sameId)
        assertEquals("Renamed", row.name)
        assertEquals(0x11.toByte(), row.secret.first())
        assertEquals(4, row.unreadCount)
        assertEquals(2, row.unreadMentionCount)
        assertEquals(NotificationLevel.MENTIONS_ONLY, row.notificationLevel)
        assertTrue(row.isPinned)
        assertEquals(123.0, row.lastMessageDate)
    }

    @Test
    fun saveChannelWithANewSecretWipesTheSlotAndResetsDerivedCounters() = runBlocking {
        val id = store.saveChannel(radioID, channelInfo(index = 3, name = "Old", secretByte = 0x33))
        db.channelDao().upsert(
            db.channelDao().getByRadioAndIndex(radioID, 3)!!.copy(unreadCount = 3, lastMessageDate = 9.0),
        )
        db.messageDao().upsert(ChatFixtures.message(text = "old", channelIndex = 3, contactID = null).toEntity())

        val sameId = store.saveChannel(radioID, channelInfo(index = 3, name = "Replacement", secretByte = 0x99))
        val row = store.fetchChannel(radioID, 3)!!

        assertEquals(id, sameId)
        assertEquals("Replacement", row.name)
        assertEquals(0, row.unreadCount)
        assertNull(row.lastMessageDate)
        assertTrue(db.messageDao().listByChannel(radioID, 3).isEmpty())
    }

    @Test
    fun saveChannelWithTheSameSecretKeepsHistoryOnRename() = runBlocking {
        store.saveChannel(radioID, channelInfo(index = 3, name = "Old", secretByte = 0x33))
        db.channelDao().upsert(
            db.channelDao().getByRadioAndIndex(radioID, 3)!!.copy(unreadCount = 3),
        )
        db.messageDao().upsert(ChatFixtures.message(text = "keep", channelIndex = 3, contactID = null).toEntity())

        store.saveChannel(radioID, channelInfo(index = 3, name = "Renamed", secretByte = 0x33))
        val row = store.fetchChannel(radioID, 3)!!

        assertEquals("Renamed", row.name)
        assertEquals(3, row.unreadCount)
        assertEquals(listOf("keep"), db.messageDao().listByChannel(radioID, 3).map { it.text })
    }

    @Test
    fun deleteChannelRemovesTheSlotsMessagesSparingOtherSlots() = runBlocking {
        val doomed = store.saveChannel(radioID, channelInfo(index = 3, name = "Doomed", secretByte = 0x33))
        store.saveChannel(radioID, channelInfo(index = 5, name = "Keep", secretByte = 0x55))
        db.messageDao().upsert(ChatFixtures.message(text = "gone", channelIndex = 3, contactID = null).toEntity())
        db.messageDao().upsert(ChatFixtures.message(text = "keep", channelIndex = 5, contactID = null).toEntity())

        store.deleteChannel(doomed)

        assertNull(store.fetchChannel(radioID, 3))
        assertTrue(db.messageDao().listByChannel(radioID, 3).isEmpty())
        assertEquals(listOf("keep"), db.messageDao().listByChannel(radioID, 5).map { it.text })
    }

    @Test
    fun batchSaveWipesSecretChangedUnconfiguredAndPrunedSlotsAndKeepsTheRest() = runBlocking {
        store.saveChannel(radioID, channelInfo(index = 1, name = "Keep", secretByte = 0x11))
        store.saveChannel(radioID, channelInfo(index = 2, name = "Taken", secretByte = 0x22))
        store.saveChannel(radioID, channelInfo(index = 3, name = "Drop", secretByte = 0x33))
        store.saveChannel(radioID, channelInfo(index = 10, name = "Orphan", secretByte = 0xAA))
        db.messageDao().upsert(ChatFixtures.message(text = "keep", channelIndex = 1, contactID = null).toEntity())
        db.messageDao().upsert(ChatFixtures.message(text = "gone2", channelIndex = 2, contactID = null).toEntity())
        db.messageDao().upsert(ChatFixtures.message(text = "gone3", channelIndex = 3, contactID = null).toEntity())
        db.messageDao().upsert(ChatFixtures.message(text = "leftover", channelIndex = 4, contactID = null).toEntity())
        db.messageDao().upsert(ChatFixtures.message(text = "orphan", channelIndex = 10, contactID = null).toEntity())

        store.batchSaveChannels(
            radioID = radioID,
            configured = listOf(
                channelInfo(index = 1, name = "Renamed", secretByte = 0x11),
                channelInfo(index = 2, name = "Taken over", secretByte = 0x99),
            ),
            unconfiguredIndices = listOf(3, 4),
            pruneBeyond = 8,
        )

        assertEquals(listOf("keep"), db.messageDao().listByChannel(radioID, 1).map { it.text })
        assertTrue(db.messageDao().listByChannel(radioID, 2).isEmpty())
        assertEquals(0, store.fetchChannel(radioID, 2)!!.unreadCount)
        assertTrue(db.messageDao().listByChannel(radioID, 3).isEmpty())
        assertTrue(db.messageDao().listByChannel(radioID, 4).isEmpty())
        assertTrue(db.messageDao().listByChannel(radioID, 10).isEmpty())
    }

    @Test
    fun aFailedBatchSaveRestoresWipedMessagesWithTheRows() = runBlocking {
        store.saveChannel(radioID, channelInfo(index = 2, name = "Keep", secretByte = 0x22))
        store.saveChannel(radioID, channelInfo(index = 3, name = "Vacated", secretByte = 0x33))
        db.messageDao().upsert(ChatFixtures.message(text = "keep", channelIndex = 2, contactID = null).toEntity())
        db.messageDao().upsert(ChatFixtures.message(text = "vacated", channelIndex = 3, contactID = null).toEntity())

        assertFailsWith<IllegalStateException> {
            store.batchSaveChannels(
                radioID = radioID,
                configured = listOf(channelInfo(index = 2, name = "Taken over", secretByte = 0x99)),
                unconfiguredIndices = listOf(3),
                pruneBeyond = 8,
                beforeCommit = { error("persist failed") },
            )
        }

        assertEquals(0x22.toByte(), store.fetchChannel(radioID, 2)!!.secret.first())
        assertEquals(listOf("keep"), db.messageDao().listByChannel(radioID, 2).map { it.text })
        assertEquals("Vacated", store.fetchChannel(radioID, 3)!!.name)
        assertEquals(listOf("vacated"), db.messageDao().listByChannel(radioID, 3).map { it.text })
    }

    @Test
    fun batchSaveUpsertsConfiguredDeletesUnconfiguredAndPrunesBeyondCap() = runBlocking {
        store.saveChannel(radioID, channelInfo(index = 1, name = "Keep", secretByte = 0x11))
        store.saveChannel(radioID, channelInfo(index = 2, name = "Drop", secretByte = 0x22))
        store.saveChannel(radioID, channelInfo(index = 5, name = "Orphan", secretByte = 0x55))

        val result = store.batchSaveChannels(
            radioID = radioID,
            configured = listOf(channelInfo(index = 1, name = "Keep", secretByte = 0x11)),
            unconfiguredIndices = listOf(2),
            pruneBeyond = 3,
        )

        assertEquals(listOf(1), result.map { it.index })
        assertNull(store.fetchChannel(radioID, 2))
        assertNull(store.fetchChannel(radioID, 5))
    }

    @Test
    fun batchSaveLeavesSlotsInNeitherListUntouched() = runBlocking {
        store.saveChannel(radioID, channelInfo(index = 1, name = "Skipped", secretByte = 0x11))

        // A slot that could not be read (neither configured nor unconfigured) must survive the persist.
        val result = store.batchSaveChannels(
            radioID = radioID,
            configured = emptyList(),
            unconfiguredIndices = emptyList(),
            pruneBeyond = null,
        )

        assertEquals(listOf(1), result.map { it.index })
    }

    @Test
    fun aFailurePartWayThroughTheSyncPassLeavesEveryRowAsItWas() = runBlocking {
        store.saveChannel(radioID, channelInfo(index = 1, name = "Keep", secretByte = 0x11))
        store.saveChannel(radioID, channelInfo(index = 2, name = "Vacated", secretByte = 0x22))

        assertFailsWith<IllegalStateException> {
            store.batchSaveChannels(
                radioID = radioID,
                configured = listOf(channelInfo(index = 1, name = "Renamed", secretByte = 0x33)),
                unconfiguredIndices = listOf(2),
                pruneBeyond = 3,
                beforeCommit = { error("persist failed") },
            )
        }

        // Committing the deletes without their replacement upserts would strand the messages keyed to the
        // vacated slots, so a failed pass must leave both the kept and the deleted rows untouched.
        assertEquals("Keep", store.fetchChannel(radioID, 1)!!.name)
        assertEquals("Vacated", store.fetchChannel(radioID, 2)!!.name)
    }

    @Test
    fun deleteMessagesForChannelRemovesOnlyThatChannelsMessages() = runBlocking {
        db.messageDao().upsert(ChatFixtures.message(text = "keep", channelIndex = 1, contactID = null).toEntity())
        db.messageDao().upsert(ChatFixtures.message(text = "clear", channelIndex = 2, contactID = null).toEntity())

        store.deleteMessagesForChannel(radioID, channelIndex = 2)

        assertEquals(listOf("keep"), db.messageDao().listByChannel(radioID, 1).map { it.text })
        assertTrue(db.messageDao().listByChannel(radioID, 2).isEmpty())
    }

    @Test
    fun deleteMessagesForChannelCascadesPendingSendsReactionsAndRepeats() = runBlocking {
        val cleared = seedChannelMessage(channelIndex = 2, text = "clear")
        val kept = seedChannelMessage(channelIndex = 1, text = "keep")

        store.deleteMessagesForChannel(radioID, channelIndex = 2)

        // A surviving pending send is re-enqueued by ChatSendQueueService.hydrate on the next connect,
        // which would broadcast text the user cleared.
        assertFalse(db.pendingSendDao().exists(cleared), "pending send cascaded")
        assertTrue(db.reactionDao().listByMessage(cleared).isEmpty(), "reactions cascaded")
        assertTrue(db.messageRepeatDao().listByMessage(cleared).isEmpty(), "message repeats cascaded")

        assertTrue(db.pendingSendDao().exists(kept), "other channel's pending send untouched")
        assertEquals(1, db.reactionDao().listByMessage(kept).size, "other channel's reaction untouched")
        assertEquals(1, db.messageRepeatDao().listByMessage(kept).size, "other channel's repeat untouched")
    }

    /** A channel message plus the pending-send, reaction, and repeat rows that hang off it. */
    private suspend fun seedChannelMessage(channelIndex: Int, text: String): Uuid {
        val messageID = ChatFixtures.freshUuid()
        db.messageDao().upsert(
            ChatFixtures.message(id = messageID, text = text, channelIndex = channelIndex, contactID = null)
                .toEntity(),
        )
        db.reactionDao().upsert(
            ReactionEntity(
                id = ChatFixtures.freshUuid(),
                messageID = messageID,
                emoji = "👍",
                senderName = "Bob",
                messageHash = "h",
                rawText = "r",
                receivedAt = 0.0,
                radioID = radioID,
                contactID = null,
            ),
        )
        db.messageRepeatDao().upsert(
            MessageRepeatEntity(
                id = ChatFixtures.freshUuid(),
                messageID = messageID,
                receivedAt = 0.0,
                pathNodes = ByteArray(0),
            ),
        )
        db.pendingSendDao().upsert(
            PendingSendEntity(
                id = ChatFixtures.freshUuid(),
                radioID = radioID,
                messageID = messageID,
                kindRawValue = 0,
                isResend = false,
                messageText = text,
                messageTimestamp = 0,
                sequence = channelIndex,
                enqueuedAt = 0.0,
            ),
        )
        return messageID
    }

    @Test
    fun clearingCountersAndLastMessageZeroesThemAndDropsThePreview() = runBlocking {
        val id = store.saveChannel(radioID, channelInfo(index = 1, name = "Alpha", secretByte = 0x11))
        db.channelDao().upsert(
            db.channelDao().getByRadioAndIndex(radioID, 1)!!
                .copy(unreadCount = 3, unreadMentionCount = 1, lastMessageDate = 9.0),
        )

        store.updateChannelLastMessage(id, null)
        store.clearChannelUnreadCount(id)
        store.clearChannelUnreadMentionCount(id)

        val row = store.fetchChannel(radioID, 1)!!
        assertEquals(0, row.unreadCount)
        assertEquals(0, row.unreadMentionCount)
        assertNull(row.lastMessageDate)
    }

    private companion object {
        const val SECRET_BYTES = 16
    }
}

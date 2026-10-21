// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreBatchSyncTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/PersistenceStore+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/DeviceDTO+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Helpers/MessageDTO+Testing.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import android.database.sqlite.SQLiteConstraintException
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatchSlotHistoryOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())
    private val at = Instant.ofEpochSecond(1_704_067_200)

    private data class Slot(
        val channel: ChannelDTO,
        val messages: List<MessageDTO>,
        val reactions: List<ReactionDTO>,
    )

    @Before fun seedOriginalDevice() = runBlocking {
        val device = DeviceDTO(
            id = radio.value, radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.0.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025", maxContacts = 100u,
            maxChannels = 8u, multiAcks = 0u, lastConnected = at, lastContactSync = 0u, isActive = true,
        )
        store.saveDevice(device)
        assertEquals(device, store.fetchDevice(radio))
    }

    @Test fun deleteOriginalIncomingSlotAndReactionsSparesTheOtherOriginalSlot() = runTest {
        val doomed = seedSlot(3u, 0x33)
        val survivor = seedSlot(5u, 0x55, count = 1)
        val doomedIds = store.fetchMessages(radio, 3u, limit = 10).map { it.id }
        assertEquals(doomed.messages.map { it.id }.toSet(), doomedIds.toSet())

        store.deleteChannel(EntityKey(radio, doomed.channel.id))

        assertNull(store.fetchChannel(radio, 3u))
        requireErased(doomed)
        for (id in doomedIds) assertTrue(store.fetchReactions(EntityKey(radio, id)).isEmpty())
        assertEquals(1, store.fetchMessages(radio, 5u, limit = 10).size)
        requireUnchanged(survivor)
    }

    @Test fun replacementSecretClearsTheOriginalIncomingHistoryAndCounters() = runTest {
        val original = seedSlot(3u, 0x33)

        store.saveChannel(radio, info(3u, "Replacement", 0x99))

        val row = requireNotNull(store.fetchChannel(radio, 3u))
        assertEquals("Replacement", row.name)
        assertEquals(0L, row.unreadCount)
        assertNull(row.lastMessageDate)
        assertEquals(original.channel.copy(name = "Replacement", secret = secret(0x99), unreadCount = 0), row)
        requireErased(original)
    }

    @Test fun sameSecretRenameKeepsAllThreeOriginalIncomingMessagesAndReactions() = runTest {
        val original = seedSlot(3u, 0x33)

        store.saveChannel(radio, info(3u, "Renamed", 0x33))

        val row = requireNotNull(store.fetchChannel(radio, 3u))
        assertEquals("Renamed", row.name)
        assertEquals(3L, row.unreadCount)
        assertEquals(3, store.fetchMessages(radio, 3u, limit = 10).size)
        requireUnchanged(original, name = "Renamed")
    }

    @Test fun batchPreservesTheOriginalIncomingOccupantAndWipesAllThreeRemovalKinds() = runTest {
        val kept = seedSlot(1u, 0x11)
        val replaced = seedSlot(2u, 0x22)
        val empty = seedSlot(3u, 0x33)
        val pruned = seedSlot(10u, 0xAA)
        val leftover = message(4u, "leftover", MessageDirection.OUTGOING)
        store.saveMessage(leftover)
        assertEquals(leftover, store.fetchMessage(EntityKey(radio, leftover.id)))
        assertNull(store.fetchChannel(radio, 4u))

        store.batchSaveChannels(
            radio, SnapshotList.of(info(1u, "Renamed", 0x11), info(2u, "Taken over", 0x99)),
            SnapshotList.of(3u, 4u), 8u,
        )

        assertEquals(3, store.fetchMessages(radio, 1u, limit = 10).size)
        requireUnchanged(kept, name = "Renamed")
        assertEquals(0L, requireNotNull(store.fetchChannel(radio, 2u)).unreadCount)
        assertEquals(
            replaced.channel.copy(name = "Taken over", secret = secret(0x99), unreadCount = 0),
            store.fetchChannel(radio, 2u),
        )
        for (slot in listOf(replaced, empty, pruned)) requireErased(slot)
        assertTrue(store.fetchMessages(radio, 4u, limit = 10).isEmpty())
        assertNull(store.fetchMessage(EntityKey(radio, leftover.id)))
        assertNull(store.fetchChannel(radio, 3u))
        assertNull(store.fetchChannel(radio, 10u))
        assertEquals(listOf(1.toUByte(), 2.toUByte()), store.fetchChannels(radio).map { it.index })
    }

    @Test fun actualPostStagingFailureRestoresBothOriginalRowsMessagesAndReactions() = runTest {
        val changed = seedSlot(2u, 0x22)
        val removed = seedSlot(3u, 0x33)
        val sql = db.openHelper.writableDatabase
        try {
            // Like the source hook, fail only after both complete slot wipes, before the transaction commits.
            sql.execSQL(
                """
                CREATE TRIGGER wp501_batch_late_failure AFTER DELETE ON channels
                WHEN OLD.`index` = 3
                  AND NOT EXISTS (SELECT 1 FROM channels WHERE `index` = 3)
                  AND NOT EXISTS (SELECT 1 FROM messages WHERE channelIndex IN (2, 3))
                  AND NOT EXISTS (SELECT 1 FROM reactions WHERE channelIndex IN (2, 3))
                  AND EXISTS (
                    SELECT 1 FROM channels WHERE `index` = 2 AND name = 'Taken over'
                      AND hex(secret) = '99999999999999999999999999999999' AND unreadCount = 0
                  )
                BEGIN SELECT RAISE(FAIL, 'test'); END
                """.trimIndent(),
            )

            val failure = assertFailsWith<PersistenceStoreException> {
                store.batchSaveChannels(
                    radio, SnapshotList.of(info(2u, "Taken over", 0x99)), SnapshotList.of(3u), 8u,
                )
            }
            assertEquals(PersistenceStoreError.SaveFailed("batchSaveChannels"), failure.error)
            assertTrue(failure.cause is SQLiteConstraintException)
            assertTrue(requireNotNull(failure.cause?.message).contains("test"))
            sql.execSQL("DROP TRIGGER wp501_batch_late_failure")

            assertEquals(secret(0x22), requireNotNull(store.fetchChannel(radio, 2u)).secret)
            assertEquals(3, store.fetchMessages(radio, 2u, limit = 10).size)
            assertNotNull(store.fetchChannel(radio, 3u))
            assertEquals(3, store.fetchMessages(radio, 3u, limit = 10).size)
            requireUnchanged(changed)
            requireUnchanged(removed)
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS wp501_batch_late_failure")
        }
    }

    private fun secret(byte: Int) = Bytes(ByteArray(16) { byte.toByte() })
    private fun info(index: UByte, name: String, byte: Int) = ChannelInfo(index, name, secret(byte))

    private fun message(index: UByte, text: String, direction: MessageDirection) = MessageDTO(
        id = UUID.randomUUID(), radioId = radio, contactID = null, channelIndex = index, text = text,
        timestamp = 1_704_067_200u, createdAt = at, direction = direction, status = MessageStatus.SENT,
        textType = TextType.PLAIN, ackCode = null, pathLength = 0u, snr = null, senderKeyPrefix = null,
        senderNodeName = null, isRead = false, replyToID = null, roundTripTime = null,
        heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0, containsSelfMention = false,
    )

    private suspend fun seedSlot(index: UByte, byte: Int, count: Int = 3): Slot {
        val id = store.saveChannel(radio, info(index, "Slot$index", byte))
        val messages = (0 until count).map { message(index, "msg $it", MessageDirection.INCOMING) }
        val reactions = messages.mapIndexed { i, message ->
            ReactionDTO(
                messageID = message.id, emoji = "\uD83D\uDC4D", senderName = "peer", messageHash = "h$i",
                rawText = "\uD83D\uDC4D h$i", receivedAt = at, channelIndex = index, radioId = radio,
            )
        }
        for ((message, reaction) in messages.zip(reactions)) {
            store.saveMessage(message)
            store.saveReaction(reaction)
            store.incrementChannelUnreadCount(EntityKey(radio, id))
        }
        val slot = Slot(
            ChannelDTO(id = id, radioId = radio, index = index, name = "Slot$index",
                secret = secret(byte), unreadCount = count.toLong()),
            messages, reactions,
        )
        requireUnchanged(slot)
        return slot
    }

    private suspend fun requireUnchanged(slot: Slot, name: String = slot.channel.name) {
        assertEquals(slot.channel.copy(name = name), store.fetchChannel(radio, slot.channel.index))
        val actual = store.fetchMessages(radio, slot.channel.index, limit = 10)
        assertEquals(slot.messages.size, actual.size)
        assertEquals(slot.messages.toSet(), actual.toSet())
        for ((message, reaction) in slot.messages.zip(slot.reactions)) {
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
            assertEquals(listOf(reaction), store.fetchReactions(EntityKey(radio, message.id)).toList())
        }
    }

    private suspend fun requireErased(slot: Slot) {
        assertTrue(store.fetchMessages(radio, slot.channel.index, limit = 10).isEmpty())
        for (message in slot.messages) {
            assertNull(store.fetchMessage(EntityKey(radio, message.id)))
            assertTrue(store.fetchReactions(EntityKey(radio, message.id)).isEmpty())
        }
    }
}

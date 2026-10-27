// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/HeardRepeatsServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-216 Real Room transaction and concurrent committed-repeat replay evidence.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeduplicationKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.services.reactions.HeardRepeatsService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HeardRepeatRoomOracleTest : RoomProcessTest() {
    private val radio = RadioId(UUID.fromString("21600000-0000-0000-0000-000000000001"))
    private val receivedAt = Instant.ofEpochSecond(1_704_067_320)
    private val stamp = 1_704_067_200u

    private fun service() = HeardRepeatsService(store).also { it.configure(radio) }

    private fun outgoing(text: String = "hello", channel: UByte = 0u, timestamp: UInt = stamp) =
        MessageDTO(
            radioId = radio, channelIndex = channel, text = text, timestamp = timestamp,
            createdAt = receivedAt, status = MessageStatus.SENT,
        )

    private fun incoming(
        text: String, path: Bytes?, channel: UByte = 0u, timestamp: UInt = stamp,
        sender: String = "TestNode", corrected: Boolean = false,
    ) = outgoing(text, channel, if (corrected) receivedAt.epochSecond.toUInt() else timestamp).copy(
        direction = MessageDirection.INCOMING, senderNodeName = sender,
        pathNodes = path, pathLength = path?.size?.toUByte() ?: 0u,
        timestampCorrected = corrected, senderTimestamp = if (corrected) timestamp else null,
        deduplicationKey = DeduplicationKey.contentBased(null, channel, sender, timestamp, text),
    )

    private fun echo(
        text: String = "hello", channel: UByte = 0u, timestamp: UInt = stamp,
        sender: String = "TestNode", path: Bytes = Bytes.of(0x42), at: Instant = receivedAt,
    ) = RxLogEntryDTO(
        id = UUID.randomUUID(), radioId = radio, receivedAt = at, snr = 8.0, rssi = -70,
        routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT, payloadVersion = 0u,
        transportCode = null, pathLength = path.size.toUByte(), pathNodes = path,
        packetPayload = Bytes.of(1, 2, 3), rawPayload = Bytes.of(1), packetHash = "test",
        channelIndex = channel, channelName = "Test", decryptStatus = DecryptStatus.SUCCESS,
        senderTimestamp = timestamp, payloadTypeBits = 5u, decodedText = "$sender: $text",
    )

    private fun key(message: MessageDTO) = EntityKey(message.radioId, message.id)

    private suspend fun assertRepeat(message: MessageDTO, entry: RxLogEntryDTO) {
        val saved = store.fetchMessageRepeats(key(message)).single()
        assertEquals(message.id, saved.messageID)
        assertEquals(entry.id, saved.rxLogEntryID)
        assertEquals(entry.pathNodes, saved.pathNodes)
        assertEquals(entry.pathLength, saved.pathLength)
        assertEquals(entry.receivedAt, saved.receivedAt)
        assertEquals(entry.snr, saved.snr)
        assertEquals(entry.rssi, saved.rssi)
        assertTrue(store.messageRepeatExists(EntityKey(radio, entry.id)))
        assertEquals(1L, assertNotNull(store.fetchMessage(key(message))).heardRepeats)
    }

    @OriginalCase("HeardRepeatsServiceTests::counts a repeat whose send is far outside the old 10s window()")
    @Test fun oldSentEchoPersistsItsMetadataCountAndEvent() = runTest {
        val message = outgoing("north repeater check", 2u)
        store.saveMessage(message)
        val service = service()
        val events = service.events()
        try {
            val entry = echo(message.text, 2u)
            assertEquals(120L, receivedAt.epochSecond - message.timestamp.toLong())
            assertEquals(1L, service.processForRepeats(entry))
            assertRepeat(message, entry)
            val event = events.first()
            assertEquals(message.id, event.messageID)
            assertEquals(1L, event.count)
        } finally {
            service.finishEvents()
        }
    }

    @OriginalCase("HeardRepeatsServiceTests::same RX log entry is counted once()")
    @Test fun replayOfTheSameRxIdentityDoesNotInsertOrIncrementAgain() = runTest {
        val message = outgoing()
        store.saveMessage(message)
        val service = service()
        val entry = echo()
        assertEquals(1L, service.processForRepeats(entry))
        val committed = store.fetchMessageRepeats(key(message)).single()
        assertNull(service.processForRepeats(entry))
        assertEquals(listOf(committed), store.fetchMessageRepeats(key(message)))
        assertRepeat(message, entry)
    }

    @OriginalCase("HeardRepeatsServiceTests::no match for unknown timestamp()")
    @Test fun unknownWireTimestampLeavesTheStoredMessageUntouched() = runTest {
        val message = outgoing(channel = 1u)
        store.saveMessage(message)
        val entry = echo(channel = 1u, timestamp = stamp + 5u)
        assertNull(service().processForRepeats(entry))
        assertEquals(message, store.fetchMessage(key(message)))
        assertTrue(store.fetchMessageRepeats(key(message)).isEmpty())
        assertFalse(store.messageRepeatExists(EntityKey(radio, entry.id)))
    }

    @OriginalCase("HeardRepeatsServiceTests::new-node rename then three TEST Hello echoes attach()")
    @Test fun renamedSenderAttachesThreeDistinctEchoIdentitiesToTheSentBody() = runTest {
        val message = outgoing("Hello")
        store.saveMessage(message)
        val service = service()
        val entries = List(3) { echo("Hello", sender = "TEST") }
        entries.forEachIndexed { index, entry ->
            assertEquals((index + 1).toLong(), service.processForRepeats(entry))
        }
        assertEquals(3L, assertNotNull(store.fetchMessage(key(message))).heardRepeats)
        val repeats = store.fetchMessageRepeats(key(message))
        assertEquals(3, repeats.size)
        assertEquals(entries.map { it.id }.toSet(), repeats.map { it.rxLogEntryID }.toSet())
        assertEquals(setOf(Bytes.of(0x42)), repeats.map { it.pathNodes }.toSet())
    }

    @OriginalCase("HeardRepeatsServiceTests::incoming RX whose path equals message pathNodes inserts nothing()")
    @Test fun canonicalIncomingPathDoesNotCreateAnExtraOrIncrement() = runTest {
        val message = incoming("same path", Bytes.of(0xAA), timestamp = stamp + 1u)
        store.saveMessage(message)
        val entry = echo(message.text, timestamp = stamp + 1u, path = Bytes.of(0xAA))
        assertNull(service().processForRepeats(entry))
        assertEquals(message, store.fetchMessage(key(message)))
        assertTrue(store.fetchMessageRepeats(key(message)).isEmpty())
        assertFalse(store.messageRepeatExists(EntityKey(radio, entry.id)))
    }

    @OriginalCase("HeardRepeatsServiceTests::clock-corrected incoming message still joins extra RX by wire timestamp()")
    @Test fun correctedDisplayTimestampStillJoinsTheOriginalWireTimestamp() = runTest {
        val message = incoming("skewed clock", Bytes.of(0xAA), 1u, 100u, corrected = true)
        store.saveMessage(message)
        val entry = echo(message.text, 1u, 100u, path = Bytes.of(0xCC))
        assertEquals(1L, service().processForRepeats(entry))
        assertRepeat(message, entry)
        val updated = assertNotNull(store.fetchMessage(key(message)))
        assertEquals(receivedAt.epochSecond.toUInt(), updated.timestamp)
        assertEquals(100u, updated.senderTimestamp)
        assertTrue(updated.timestampCorrected)
    }

    @OriginalCase("HeardRepeatsServiceTests::empty path extra is recorded as a 0-hop arrival()")
    @Test fun emptyPathIsAPersistedZeroHopExtraRatherThanUnknown() = runTest {
        val message = incoming("zero hop extra", Bytes.of(0xAA), timestamp = stamp + 3u)
        store.saveMessage(message)
        val entry = echo(message.text, timestamp = stamp + 3u, path = Bytes.EMPTY)
        assertEquals(1L, service().processForRepeats(entry))
        assertRepeat(message, entry)
        assertEquals(Bytes.EMPTY, store.fetchMessageRepeats(key(message)).single().pathNodes)
        assertEquals(0u.toUByte(), store.fetchMessageRepeats(key(message)).single().pathLength)
        assertEquals(message.pathNodes, assertNotNull(store.fetchMessage(key(message))).pathNodes)
    }

    @OriginalCase("HeardRepeatsServiceTests::harvest after save records extras from RX rows that arrived first()")
    @Test fun harvestAfterMessageSaveRecordsTheEarlierNoncanonicalRxPath() = runTest {
        val service = service()
        val first = echo("harvest me", path = Bytes.of(0xA1), at = receivedAt.minusSeconds(1))
        val second = echo("harvest me", path = Bytes.of(0xB2))
        for (entry in listOf(first, second)) {
            store.saveRxLogEntry(entry)
            assertNull(service.processForRepeats(entry))
        }
        val message = incoming("harvest me", second.pathNodes)
        store.saveMessage(message)
        service.harvestIncomingPaths(message, listOf(first, second))
        assertRepeat(message, first)
        assertFalse(store.messageRepeatExists(EntityKey(radio, second.id)))
        assertEquals(second.pathNodes, assertNotNull(store.fetchMessage(key(message))).pathNodes)
    }

    @OriginalCase("HeardRepeatsServiceTests::harvest ignores a same-stamp 0x88 whose body does not match()")
    @Test fun sameTimestampDifferentSenderAndBodyDoesNotContaminateHarvest() = runTest {
        val alice = echo("one", timestamp = 42u, sender = "Alice", path = Bytes.of(0xA1))
        val bob = echo("two", timestamp = 42u, sender = "Bob", path = Bytes.of(0xB2))
        val message = incoming("one", Bytes.of(0xC0), timestamp = 42u, sender = "Alice")
        store.saveMessage(message)
        service().harvestIncomingPaths(message, listOf(alice, bob))
        assertRepeat(message, alice)
        assertFalse(store.messageRepeatExists(EntityKey(radio, bob.id)))
        assertEquals(message.pathNodes, assertNotNull(store.fetchMessage(key(message))).pathNodes)
    }

    @OriginalCase("HeardRepeatsServiceTests::harvest adopts a matching path onto an unknown incoming message()")
    @Test fun singleUnknownPathIsAdoptedWithoutCreatingARepeat() = runTest {
        val message = incoming("adopt me", null, timestamp = stamp + 5u)
        store.saveMessage(message)
        val entry = echo(message.text, timestamp = stamp + 5u, path = Bytes.of(0xAA))
        service().harvestIncomingPaths(message, listOf(entry))
        val updated = assertNotNull(store.fetchMessage(key(message)))
        assertEquals(entry.pathNodes, updated.pathNodes)
        assertEquals(1u.toUByte(), updated.pathLength)
        assertEquals(0L, updated.heardRepeats)
        assertTrue(store.fetchMessageRepeats(key(message)).isEmpty())
        assertFalse(store.messageRepeatExists(EntityKey(radio, entry.id)))
    }

    @Test fun concurrentReplaysOfCommittedRxIdentitiesDoNotDuplicateRowsOrCounts() = runTest {
        val message = outgoing()
        store.saveMessage(message)
        val service = service()
        val entries = List(3) { echo(at = receivedAt.plusSeconds(it.toLong())) }
        for ((index, entry) in entries.withIndex()) {
            assertEquals((index + 1).toLong(), service.processForRepeats(entry))
        }
        val committed = store.fetchMessageRepeats(key(message))
        val start = CompletableDeferred<Unit>()
        val replays = List(12) { index ->
            async { start.await(); service.processForRepeats(entries[index % entries.size]) }
        }
        start.complete(Unit)
        assertEquals(List<Long?>(12) { null }, replays.awaitAll())
        assertEquals(committed, store.fetchMessageRepeats(key(message)))
        assertEquals(3L, assertNotNull(store.fetchMessage(key(message))).heardRepeats)
    }

    @Test fun concurrentConditionalAdoptionCommitsOnlyOneCanonicalPath() = runTest {
        val message = incoming("one canonical", null)
        store.saveMessage(message)
        val paths = listOf(Bytes.EMPTY, Bytes.of(0xA1), Bytes.of(0xB2, 0xB3))
        val start = CompletableDeferred<Unit>()
        val writes = paths.map { path ->
            async {
                start.await()
                path to store.adoptIncomingPathIfUnknown(key(message), path, path.size.toUByte())
            }
        }
        start.complete(Unit)
        val winner = writes.awaitAll().filter { it.second }.single().first
        val saved = assertNotNull(store.fetchMessage(key(message)))
        assertEquals(winner, saved.pathNodes)
        assertEquals(winner.size.toUByte(), saved.pathLength)
        assertEquals(0L, saved.heardRepeats)
        assertTrue(store.fetchMessageRepeats(key(message)).isEmpty())
    }

    @Test fun concurrentRepeatCountTransactionsReturnEveryDistinctCommittedValue() = runTest {
        val message = outgoing()
        store.saveMessage(message)
        val start = CompletableDeferred<Unit>()
        val writes = List(16) {
            async { start.await(); store.incrementMessageHeardRepeats(key(message)) }
        }
        start.complete(Unit)
        assertEquals((1L..16L).toSet(), writes.awaitAll().toSet())
        assertEquals(16L, assertNotNull(store.fetchMessage(key(message))).heardRepeats)
        assertTrue(store.fetchMessageRepeats(key(message)).isEmpty())
    }

    @Test fun missingParentRepeatTransactionFailsWithoutLeavingARow() = runTest {
        val repeat = MessageRepeatDTO(
            messageID = UUID.randomUUID(), receivedAt = receivedAt, pathNodes = Bytes.of(0xA1),
            rxLogEntryID = UUID.randomUUID(),
        )
        val failure = assertFailsWith<PersistenceStoreException> { store.saveMessageRepeat(radio, repeat) }
        assertEquals(PersistenceStoreError.MessageNotFound, failure.error)
        assertTrue(store.fetchMessageRepeats(EntityKey(radio, repeat.messageID)).isEmpty())
        assertFalse(store.messageRepeatExists(EntityKey(radio, assertNotNull(repeat.rxLogEntryID))))
    }
}

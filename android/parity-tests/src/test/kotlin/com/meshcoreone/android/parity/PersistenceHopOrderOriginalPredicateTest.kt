// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceHopOrderOriginalPredicateTest : RoomProcessTest() {
    private fun frame(name: String = "Advertiser"): ContactFrame {
        val timestamp = Instant.now().epochSecond.toUInt()
        return ContactFrame(
            Bytes(ByteArray(32) { 0x45 }), ContactType.CHAT, 0u, 2u, Bytes.of(1, 2), name,
            timestamp, 37.7749, -122.4194, timestamp,
        )
    }

    private suspend fun radio(): RadioId {
        val id = UUID.randomUUID()
        val device = DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
        store.saveDevice(device)
        return device.radioId
    }

    private suspend fun conversation(): EntityKey {
        val radio = radio()
        return EntityKey(radio, store.saveContact(radio, frame("TestContact")).id)
    }

    @OriginalCase("PersistenceStoreTests::setInboundHopCount round-trips onto an existing discovered node()")
    @Test fun hopRoundTripRetainsOriginalCountAndBothStoredValues() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val node = store.upsertDiscoveredNode(radio, frame()).node
        store.setInboundHopCount(radio, node.publicKey, 4, 100u)

        val fetched = store.fetchDiscoveredNodes(radio)
        assertEquals(1, fetched.size)
        assertEquals(4L, fetched.first().inboundHopCount)
        assertEquals(100u, fetched.first().inboundHopAdvertTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::setInboundHopCount is a no-op when no matching row exists()")
    @Test fun unknownHopWriteRetainsTheOriginalNilAdvertTimestamp() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val unknown = Bytes(ByteArray(32) { 0x46 })
        store.setInboundHopCount(radio, unknown, 2, null)

        assertTrue(store.fetchDiscoveredNodes(radio).isEmpty())
    }

    @OriginalCase("PersistenceStoreTests::setInboundHopCount keeps the closest copy within the same broadcast()")
    @Test fun sameBroadcastRetainsTheOriginalThreeToZeroThenFiveSequence() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val node = store.upsertDiscoveredNode(radio, frame()).node
        store.setInboundHopCount(radio, node.publicKey, 3, 200u)
        store.setInboundHopCount(radio, node.publicKey, 0, 200u)
        assertEquals(0L, store.fetchDiscoveredNodes(radio).first().inboundHopCount)

        store.setInboundHopCount(radio, node.publicKey, 5, 200u)
        assertEquals(0L, store.fetchDiscoveredNodes(radio).first().inboundHopCount)
    }

    @OriginalCase("PersistenceStoreTests::a newer advert timestamp raises the inbound hop count()")
    @Test fun newerAdvertRetainsTheOriginalDirectThenFartherPredicates() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val node = store.upsertDiscoveredNode(radio, frame()).node
        store.setInboundHopCount(radio, node.publicKey, 0, 100u)
        assertEquals(0L, store.fetchDiscoveredNodes(radio).first().inboundHopCount)

        store.setInboundHopCount(radio, node.publicKey, 3, 200u)
        val fetched = store.fetchDiscoveredNodes(radio)
        assertEquals(3L, fetched.first().inboundHopCount)
        assertEquals(200u, fetched.first().inboundHopAdvertTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::an older advert timestamp is a no-op even when it has a closer hop count()")
    @Test fun olderAdvertRetainsTheOriginalThreeAtTwoHundredThenOneAtOneHundred() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val node = store.upsertDiscoveredNode(radio, frame()).node
        store.setInboundHopCount(radio, node.publicKey, 3, 200u)
        store.setInboundHopCount(radio, node.publicKey, 1, 100u)

        val fetched = store.fetchDiscoveredNodes(radio)
        assertEquals(3L, fetched.first().inboundHopCount)
        assertEquals(200u, fetched.first().inboundHopAdvertTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::upsertDiscoveredNode does not reset a stored inbound hop count()")
    @Test fun advertUpsertRetainsTheOriginalTwoHopAndFloodZeroCoordinateSetup() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val node = store.upsertDiscoveredNode(radio, frame()).node
        store.setInboundHopCount(radio, node.publicKey, 2, 50u)
        val timestamp = Instant.now().epochSecond.toUInt()
        store.upsertDiscoveredNode(radio, ContactFrame(
            node.publicKey, ContactType.CHAT, 0u, 255u, Bytes.EMPTY, "Advertiser",
            timestamp, 0.0, 0.0, timestamp,
        ))

        assertEquals(2L, store.fetchDiscoveredNodes(radio).first().inboundHopCount)
    }

    @OriginalCase("PersistenceStoreTests::an inbound hop heard before the node row exists is applied when the advert upsert lands()")
    @Test fun firstAdvertRetainsTheOriginalSingleBufferedThreeHopWrite() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val frame = frame()
        store.setInboundHopCount(radio, frame.publicKey, 3, 100u)
        assertTrue(store.fetchDiscoveredNodes(radio).isEmpty())

        val result = store.upsertDiscoveredNode(radio, frame)
        assertTrue(result.isNew)
        assertEquals(3L, result.node.inboundHopCount)
        assertEquals(3L, store.fetchDiscoveredNodes(radio).first().inboundHopCount)
    }

    @OriginalCase("PersistenceStoreTests::a buffered inbound hop keeps the closest copy of the same broadcast before the row lands()")
    @Test fun bufferedBroadcastRetainsTheOriginalFourOneSixAndReturnedNodePredicate() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val frame = frame()
        store.setInboundHopCount(radio, frame.publicKey, 4, 100u)
        store.setInboundHopCount(radio, frame.publicKey, 1, 100u)
        store.setInboundHopCount(radio, frame.publicKey, 6, 100u)

        assertEquals(1L, store.upsertDiscoveredNode(radio, frame).node.inboundHopCount)
    }

    @OriginalCase("PersistenceStoreTests::DiscoveredNodeDTO carries inboundHopCount and equality discriminates on it()")
    @Test fun discoveredDtoRetainsTheOriginalOneHopNilTimestampEqualityInput() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val node = store.upsertDiscoveredNode(radio, frame()).node
        store.setInboundHopCount(radio, node.publicKey, 1, null)
        val fetched = store.fetchDiscoveredNodes(radio).firstOrNull()
        assertEquals(1L, fetched?.inboundHopCount)

        val base = assertNotNull(fetched)
        val mutated = base.copy(inboundHopCount = 99, inboundHopAdvertTimestamp = null)
        assertNotEquals(base, mutated)
    }

    @OriginalCase("PersistenceStoreTests::Interleaved backlog from multiple senders reassembles in sortDate (send) order()")
    @Test fun interleavedBacklogRetainsTheOriginalPendingModelAndScrambledReceiveOrder() = runTest {
        val contact = conversation()
        val drain = Instant.ofEpochSecond(2_000_000)
        val send = Instant.ofEpochSecond(1_000_000)
        for ((offset, index) in listOf(2, 0, 3, 1).withIndex()) {
            store.saveMessage(MessageDTO(
                radioId = contact.radioId, contactID = contact.id, text = "Send $index",
                timestamp = 1_000_000u + index.toUInt(), createdAt = drain.plusSeconds(offset.toLong()),
                sortDate = send.plusSeconds(index.toLong()),
            ))
        }

        assertEquals(listOf("Send 0", "Send 1", "Send 2", "Send 3"), store.fetchMessages(contact).map { it.text })
    }

    @OriginalCase("PersistenceStoreTests::Live message stays last even when an earlier-sortDate backlog row is inserted after it()")
    @Test fun liveRowRetainsTheOriginalFullOrderAndIndependentLastPredicate() = runTest {
        val contact = conversation()
        val now = Instant.ofEpochSecond(3_000_000)
        store.saveMessage(MessageDTO(
            radioId = contact.radioId, contactID = contact.id, text = "Live",
            timestamp = 3_000_000u, createdAt = now, sortDate = now,
        ))
        val earlier = now.minusSeconds(3600)
        store.saveMessage(MessageDTO(
            radioId = contact.radioId, contactID = contact.id, text = "Backlog",
            timestamp = earlier.epochSecond.toUInt(), createdAt = now.plusSeconds(60), sortDate = earlier,
        ))

        val messages = store.fetchMessages(contact)
        assertEquals(listOf("Backlog", "Live"), messages.map { it.text })
        assertEquals("Live", messages.lastOrNull()?.text)
    }

    @OriginalCase("PersistenceStoreTests::Equal sortDate and timestamp fall back to createdAt order (tertiary key)()")
    @Test fun tertiaryOrderingRetainsTheOriginalEqualPrimaryAndSecondaryKeys() = runTest {
        val contact = conversation()
        val sort = Instant.ofEpochSecond(4_000_000)
        val created = Instant.ofEpochSecond(5_000_000)
        for (index in 0 until 3) {
            store.saveMessage(MessageDTO(
                radioId = contact.radioId, contactID = contact.id, text = "Tie $index",
                timestamp = 4_000_000u, createdAt = created.plusSeconds(index.toLong()), sortDate = sort,
            ))
        }

        assertEquals(listOf("Tie 0", "Tie 1", "Tie 2"), store.fetchMessages(contact).map { it.text })
    }

    @OriginalCase("PersistenceStoreTests::Backlog block orders by send time within a shared drain anchor()")
    @Test fun drainAnchorRetainsOriginalDifferentSendersAndIncomingPendingDefaults() = runTest {
        val radio = radio()
        val anchor = Instant.ofEpochSecond(1_700_000_000)
        for ((sender, time, offset) in listOf(
            Triple("Carol", 300u, 0L), Triple("Bob", 200u, 1L), Triple("Alice", 100u, 2L),
        )) {
            store.saveMessage(MessageDTO(
                radioId = radio, channelIndex = 0u, text = sender, timestamp = time,
                createdAt = anchor.plusSeconds(offset), sortDate = anchor,
                direction = MessageDirection.INCOMING, senderNodeName = sender,
            ))
        }

        assertEquals(listOf("Alice", "Bob", "Carol"), store.fetchMessages(radio, 0u).map { it.text })
    }

    @OriginalCase("PersistenceStoreTests::adoptIncomingPathIfUnknown writes the first path onto a nil column()")
    @Test fun firstIncomingPathRetainsOriginalTextDirectionSignalAndAllStoredPredicates() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val message = MessageDTO(
            radioId = radio, channelIndex = 0u, text = "unknown path",
            timestamp = Instant.now().epochSecond.toUInt(), direction = MessageDirection.INCOMING,
            status = MessageStatus.SENT, snr = 7.5, pathNodes = null,
        )
        store.saveMessage(message)
        val key = EntityKey(radio, message.id)

        assertTrue(store.adoptIncomingPathIfUnknown(key, Bytes.of(0xAA), 1u))
        assertFalse(store.adoptIncomingPathIfUnknown(key, Bytes.of(0xBB), 2u))
        val fetched = store.fetchMessage(key)
        assertEquals(Bytes.of(0xAA), fetched?.pathNodes)
        assertEquals(1u.toUByte(), fetched?.pathLength)
        assertEquals(0L, fetched?.heardRepeats)
        assertEquals(7.5, fetched?.snr)
    }

    @OriginalCase("PersistenceStoreTests::adoptIncomingPathIfUnknown returns false when the row is missing()")
    @Test fun missingIncomingPathRetainsTheOriginalEmptyStoreAndOneByteLength() = runTest {
        assertFalse(store.adoptIncomingPathIfUnknown(
            EntityKey(RadioId(UUID.randomUUID()), UUID.randomUUID()), Bytes.of(0xAA), 1u,
        ))
    }
}

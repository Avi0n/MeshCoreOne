// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.ChannelRegionUpdate
import com.meshcoreone.android.core.contracts.domain.DirectRegionUpdate
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PersistenceRxOriginalPredicateTest : RoomProcessTest() {
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

    private fun rx(
        radio: RadioId, timestamp: UInt? = null, scope: String? = null,
        matches: SnapshotList<String> = SnapshotList.empty(), bits: UByte = 5u,
        transport: Bytes? = null, channel: UByte = 1u, receivedAt: Instant = Instant.now(),
        path: List<UByte> = listOf(0x42u),
    ): RxLogEntryDTO = RxLogEntryDTO.fromParsed(
        radio, ParsedRxLogData(
            snr = 10.5, rssi = -65, rawPayload = Bytes.of(0x15, 1, 2, 3),
            routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT,
            payloadVersion = 0u, payloadTypeBits = bits, transportCode = transport,
            pathLength = 1u, pathNodes = path, packetPayload = Bytes.of(0xAB, 0xCD, 0xEF),
        ),
        receivedAt = receivedAt, channelIndex = channel, channelName = "TestChannel",
        decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = timestamp,
        regionScope = scope, regionScopeMatches = matches, decodedText = "Hello mesh!",
    )

    private fun channel(radio: RadioId, index: UByte, timestamp: UInt): MessageDTO = MessageDTO(
        radioId = radio, channelIndex = index, text = "Test channel message", timestamp = timestamp,
        direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
    )

    @OriginalCase("PersistenceStoreTests::Save and fetch RxLogEntry preserves senderTimestamp()")
    @Test fun aSingleRxRowPreservesTheOriginalSenderTimestamp() = runTest {
        val radio = radio()
        store.saveRxLogEntry(rx(radio, timestamp = 1_703_123_456u))

        val entries = store.fetchRxLogEntries(radio)
        assertEquals(1, entries.size)
        assertEquals(1_703_123_456u, entries.first().senderTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::Save and fetch RxLogEntry with nil senderTimestamp()")
    @Test fun aSingleRxRowPreservesTheOriginalAbsentTimestamp() = runTest {
        val radio = radio()
        store.saveRxLogEntry(rx(radio, timestamp = null))

        val entries = store.fetchRxLogEntries(radio)
        assertEquals(1, entries.size)
        assertNull(entries.first().senderTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::RxLogEntryDTO init from model preserves senderTimestamp()")
    @Test fun senderTimestampRetainsTheOriginalModelToUnsignedProjection() = runTest {
        val radio = radio()
        val dto = rx(radio, timestamp = 1_703_123_456u)
        store.saveRxLogEntry(dto)
        assertEquals(1_703_123_456u, store.fetchRxLogEntries(radio).first().senderTimestamp)

        store.flushPendingRxLogEntries()
        assertEquals(1_703_123_456L, assertNotNull(db.rxLogs().byId(radio.value, dto.id)).senderTimestamp)
        assertEquals(1_703_123_456u, store.fetchRxLogEntries(radio).first().senderTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::fetchRxLogEntries returns every matching channel row in receivedAt order()")
    @Test fun correlatedRxRowsRetainBothOriginalCountAndArrivalOrder() = runTest {
        val radio = radio()
        val earlier = Instant.ofEpochSecond(1_700_000_000)
        store.saveRxLogEntry(rx(radio, 42u, receivedAt = earlier.plusSeconds(2), path = listOf(0xBBu)))
        store.saveRxLogEntry(rx(radio, 42u, receivedAt = earlier, path = listOf(0xAAu)))
        store.saveRxLogEntry(rx(radio, 42u, channel = 2u, receivedAt = earlier, path = listOf(0xCCu)))
        store.saveRxLogEntry(rx(radio, 43u, receivedAt = earlier, path = listOf(0xDDu)))

        val matches = store.fetchRxLogEntries(radio, 1u, 42u)
        assertEquals(2, matches.size)
        assertEquals(listOf(Bytes.of(0xAA), Bytes.of(0xBB)), matches.map { it.pathNodes })
    }

    @OriginalCase("PersistenceStoreTests::saveRxLogEntry forwards regionScope to the persisted model()")
    @Test fun savingRxRegionRetainsBothOriginalStorageFields() = runTest {
        val radio = radio()
        val dto = rx(radio, 1_703_000_000u, "Germany", SnapshotList.of("Germany"))
        store.saveRxLogEntry(dto)
        val entries = store.fetchRxLogEntries(radio)
        assertEquals("Germany", entries.first().regionScope)
        assertEquals(listOf("Germany"), entries.first().regionScopeMatches)

        store.flushPendingRxLogEntries()
        val row = assertNotNull(db.rxLogs().byId(radio.value, dto.id))
        assertEquals("Germany", row.regionScope)
        assertEquals(listOf("Germany"), row.regionScopeMatches)
    }

    @OriginalCase("PersistenceStoreTests::batchUpdateRxLogRegion writes dual region fields()")
    @Test fun rxRegionRewriteRetainsTheOriginalTimestampCodeAndDualFieldTransition() = runTest {
        val radio = radio()
        store.saveRxLogEntry(rx(
            radio, 1_703_000_050u, "Germany", SnapshotList.of("Germany"), transport = Bytes.of(0x12, 0x34),
        ))
        val id = store.fetchRxLogEntries(radio).first().id
        store.batchUpdateRxLogRegion(radio, SnapshotList.of(
            RxLogRegionUpdate(id, null, SnapshotList.of("de-by", "de-hh")),
        ))

        val updated = store.fetchRxLogEntries(radio).first()
        assertNull(updated.regionScope)
        assertEquals(listOf("de-by", "de-hh"), updated.regionScopeMatches)
    }

    @OriginalCase("PersistenceStoreTests::fetchEntriesWithTransportCode includes labeled rows and honors limit()")
    @Test fun transportCodeSelectionRetainsOriginalCountNonNullCodesAndExactArrivalDates() = runTest {
        val radio = radio()
        val base = Instant.ofEpochSecond(1_700_000_000)
        for (index in 0 until 5) {
            store.saveRxLogEntry(rx(
                radio, index.toUInt(), if (index == 0) "Germany" else null,
                if (index == 0) SnapshotList.of("Germany") else SnapshotList.empty(),
                transport = Bytes.of(index, 2), receivedAt = base.plusSeconds(index.toLong()),
            ))
        }
        store.saveRxLogEntry(rx(radio, 99u, transport = null, receivedAt = base.plusSeconds(100)))

        val entries = store.fetchEntriesWithTransportCode(radio, 2)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.transportCode != null })
        assertEquals(listOf(base.plusSeconds(4), base.plusSeconds(3)), entries.map { it.receivedAt })
        assertEquals(listOf(4u, 3u), entries.map { it.senderTimestamp })
    }

    @OriginalCase("PersistenceStoreTests::saveRxLogEntry preserves payloadTypeBits including unknown nibbles()")
    @Test fun unknownNibbleStorageRetainsTheOriginalWireTimestampAndBits() = runTest {
        val radio = radio()
        store.saveRxLogEntry(rx(radio, 1_703_000_001u, bits = 0x0Cu))

        assertEquals(0x0Cu.toUByte(), store.fetchRxLogEntries(radio).first().payloadTypeBits)
    }

    @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion back-fills normal-case message via timestamp fallback()")
    @Test fun normalChannelBackfillRetainsBothOriginalRegionFields() = runTest {
        val radio = radio()
        store.saveMessage(channel(radio, 0u, 1_703_111_111u))
        store.batchUpdateChannelMessageRegion(radio, SnapshotList.of(
            ChannelRegionUpdate(0u, 1_703_111_111u, "Germany", SnapshotList.of("Germany")),
        ))

        val saved = store.fetchMessages(radio, 0u).first()
        assertEquals("Germany", saved.regionScope)
        assertEquals(listOf("Germany"), saved.regionScopeMatches)
    }

    @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion back-fills timestamp-corrected message()")
    @Test fun correctedChannelBackfillRetainsOriginalWireClockAndBothRegionFields() = runTest {
        val radio = radio()
        store.saveMessage(channel(radio, 1u, Instant.now().epochSecond.toUInt()).copy(
            senderTimestamp = 1_703_222_222u,
        ))
        store.batchUpdateChannelMessageRegion(radio, SnapshotList.of(
            ChannelRegionUpdate(1u, 1_703_222_222u, "USA", SnapshotList.of("USA")),
        ))

        val saved = store.fetchMessages(radio, 1u).first()
        assertEquals("USA", saved.regionScope)
        assertEquals(listOf("USA"), saved.regionScopeMatches)
    }

    @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion writes multi-match regionScopeMatches with nil scope()")
    @Test fun ambiguousChannelBackfillRetainsTheOriginalNilScopeAndMatchSet() = runTest {
        val radio = radio()
        store.saveMessage(channel(radio, 3u, 1_703_666_666u))
        store.batchUpdateChannelMessageRegion(radio, SnapshotList.of(
            ChannelRegionUpdate(3u, 1_703_666_666u, null, SnapshotList.of("First", "Second")),
        ))

        val saved = store.fetchMessages(radio, 3u).first()
        assertNull(saved.regionScope)
        assertEquals(setOf("First", "Second"), saved.regionScopeMatches.toSet())
    }

    @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion skips outgoing messages()")
    @Test fun outgoingChannelBackfillRetainsTheOriginalSentSetupAndExclusion() = runTest {
        val radio = radio()
        store.saveMessage(channel(radio, 2u, 1_703_333_333u).copy(
            direction = MessageDirection.OUTGOING, status = MessageStatus.SENT,
        ))
        store.batchUpdateChannelMessageRegion(radio, SnapshotList.of(
            ChannelRegionUpdate(2u, 1_703_333_333u, "France", SnapshotList.of("France")),
        ))

        assertNull(store.fetchMessages(radio, 2u).first().regionScope)
    }

    @OriginalCase("PersistenceStoreTests::batchUpdateDMMessageRegion back-fills DM by sender prefix byte()")
    @Test fun directBackfillRetainsTheOriginalAbPrefixAndBothRegionFields() = runTest {
        val radio = radio()
        val contact = UUID.randomUUID()
        store.saveMessage(MessageDTO(
            radioId = radio, contactID = contact, text = "Test message", timestamp = 1_703_444_444u,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            senderKeyPrefix = Bytes.of(0xAB, 0xCD, 0xEF, 1, 2, 3),
        ))
        store.batchUpdateDMMessageRegion(radio, SnapshotList.of(
            DirectRegionUpdate(0xABu, 1_703_444_444u, "Germany", SnapshotList.of("Germany")),
        ))

        val saved = store.fetchMessages(EntityKey(radio, contact)).first()
        assertEquals("Germany", saved.regionScope)
        assertEquals(listOf("Germany"), saved.regionScopeMatches)
    }

    @OriginalCase("PersistenceStoreTests::batchUpdateDMMessageRegion ignores DMs from other senders at same timestamp()")
    @Test fun directBackfillRetainsTwoOriginalContactsTextsAndPrefixFamilies() = runTest {
        val radio = radio()
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        store.saveMessage(MessageDTO(
            radioId = radio, contactID = alice, text = "From Alice", timestamp = 1_703_555_555u,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            senderKeyPrefix = Bytes.of(0xAA, 0x11, 0x22, 0x33, 0x44, 0x55),
        ))
        store.saveMessage(MessageDTO(
            radioId = radio, contactID = bob, text = "From Bob", timestamp = 1_703_555_555u,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            senderKeyPrefix = Bytes.of(0xBB, 0x66, 0x77, 0x88, 0x99, 0),
        ))
        store.batchUpdateDMMessageRegion(radio, SnapshotList.of(
            DirectRegionUpdate(0xAAu, 1_703_555_555u, "Germany", SnapshotList.of("Germany")),
        ))

        assertEquals("Germany", store.fetchMessages(EntityKey(radio, alice)).first().regionScope)
        assertNull(store.fetchMessages(EntityKey(radio, bob)).first().regionScope)
    }
}

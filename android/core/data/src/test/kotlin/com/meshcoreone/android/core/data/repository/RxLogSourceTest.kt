// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.PayloadType
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RxLogSourceTest : RepositoryTest() {
    @Test @OriginalCase("PersistenceStoreTests::RxLogEntryDTO(from:) falls back on out-of-range stored values instead of trapping()")
    fun storedOutOfRangeValuesUseSourceFallbacksWithoutReplacingOrDroppingEitherRow() = runTest {
        val raw = rx().toEntity().copy(routeType = 999, payloadType = -1, payloadVersion = 5000,
            pathLength = 400, channelIndex = 9999, senderTimestamp = -10)
        val healthy = rx(receivedAt = AT.minusNanos(1)).copy(
            payloadVersion = 255u, pathLength = 255u, channelIndex = 255u, senderTimestamp = UInt.MAX_VALUE,
        ).toEntity()
        db.rxLogs().insert(raw)
        db.rxLogs().insert(healthy)
        repeat(2) {
            val entries = store.fetchRxLogEntries(RADIO_A)
            assertEquals(listOf(raw.id, healthy.id), entries.map { it.id })
            val dto = entries.first()
            assertEquals(RADIO_A, dto.radioId)
            assertEquals(com.meshcoreone.android.core.protocol.event.RouteType.FLOOD, dto.routeType)
            assertEquals(PayloadType.UNKNOWN, dto.payloadType)
            assertEquals(0.toUByte(), dto.payloadVersion)
            assertEquals(0.toUByte(), dto.pathLength)
            assertNull(dto.channelIndex)
            assertNull(dto.senderTimestamp)
            assertEquals(Bytes.of(0x42), dto.pathNodes)
            assertEquals(Bytes.of(0xAB, 0xCD, 0xEF), dto.packetPayload)
            assertEquals(Bytes.of(0x15, 1, 2, 3), dto.rawPayload)
            assertEquals(255.toUByte(), entries.last().payloadVersion)
            assertEquals(255.toUByte(), entries.last().pathLength)
            assertEquals(255.toUByte(), entries.last().channelIndex)
            assertEquals(UInt.MAX_VALUE, entries.last().senderTimestamp)
            assertEquals(raw, assertNotNull(db.rxLogs().byId(RADIO_A.value, raw.id)))
            assertEquals(healthy, assertNotNull(db.rxLogs().byId(RADIO_A.value, healthy.id)))
            assertEquals(2L, db.rxLogs().count(RADIO_A.value))
            assertEquals(0, issues.size)
        }
    }

    @Test @OriginalCase("PersistenceStoreTests::Save and fetch RxLogEntry preserves senderTimestamp()")
    @OriginalCase("PersistenceStoreTests::Save and fetch RxLogEntry with nil senderTimestamp()")
    @OriginalCase("PersistenceStoreTests::RxLogEntryDTO init from model preserves senderTimestamp()")
    @OriginalCase("PersistenceStoreTests::findRxLogEntry sees an insert before the batch save()")
    @OriginalCase("PersistenceStoreTests::saveRxLogEntry forwards regionScope to the persisted model()")
    @OriginalCase("PersistenceStoreTests::saveRxLogEntry preserves payloadTypeBits including unknown nibbles()")
    fun uncommittedRxRowsAreVisibleAndRetainNullableHighBitAndRegionFields() = runTest {
        val a = rx(timestamp = 1_703_123_456u).copy(regionScope = "Germany",
            regionScopeMatches = SnapshotList.of("Germany"), payloadTypeBits = 0x0Cu)
        val b = rx(timestamp = null, receivedAt = AT.plusNanos(1))
        store.saveRxLogEntry(a)
        assertEquals(0L, store.rxInitiatedSaveCount)
        assertNull(db.rxLogs().byId(RADIO_A.value, a.id))
        assertEquals(a.id, assertNotNull(store.findRxLogEntry(RADIO_A, 1u, 1_703_123_456u)).id)
        store.saveRxLogEntry(b)
        val rows = store.fetchRxLogEntries(RADIO_A)
        assertEquals(listOf(null, 1_703_123_456u), rows.map { it.senderTimestamp })
        assertEquals(0x0C.toUByte(), rows.last().payloadTypeBits)
        assertEquals("Germany", rows.last().regionScope)
        assertEquals(listOf("Germany"), rows.last().regionScopeMatches)
        assertNull(rows.last().decodedText)
        store.flushPendingRxLogEntries()
        assertEquals(1_703_123_456L, assertNotNull(db.rxLogs().byId(RADIO_A.value, a.id)).senderTimestamp)
    }

    @Test @OriginalCase("PersistenceStoreTests::saveRxLogEntry commits once per batchSize inserts()")
    @OriginalCase("PersistenceStoreTests::flushPendingRxLogEntries commits a partial batch()")
    fun batchesCommitAtExactlyTwentyAndExplicitFlushPersistsAPartialBatch() = runTest {
        for (i in 0 until 20) store.saveRxLogEntry(rx(timestamp = i.toUInt(), receivedAt = AT.plusNanos(i.toLong())))
        assertEquals(20L, db.rxLogs().count(RADIO_A.value))
        assertEquals(1L, store.rxInitiatedSaveCount)
        assertEquals(20, store.fetchRxLogEntries(RADIO_A, 40).size)
        val partial = rx(timestamp = 20u, receivedAt = AT.plusNanos(20))
        store.saveRxLogEntry(partial)
        assertNull(db.rxLogs().byId(RADIO_A.value, partial.id))
        assertEquals(1L, store.rxInitiatedSaveCount)
        store.flushPendingRxLogEntries()
        assertEquals(2L, store.rxInitiatedSaveCount)
        val reopenedStore = newStore()
        try {
            assertEquals(21, reopenedStore.fetchRxLogEntries(RADIO_A, 40).size)
        } finally {
            reopenedStore.close()
        }
    }

    @Test @OriginalCase("PersistenceStoreTests::RX log prune is deferred until threshold is exceeded()")
    fun retentionThresholdIsStrictlyGreaterThanElevenHundred() = runTest {
        for (i in 0 until 1100) {
            store.saveRxLogEntry(rx(timestamp = i.toUInt(), receivedAt = AT.plusNanos(i.toLong())))
            store.pruneRxLogEntries(RADIO_A)
        }
        assertEquals(1100, store.fetchRxLogEntries(RADIO_A, 1200).size)
        store.saveRxLogEntry(rx(timestamp = 1100u, receivedAt = AT.plusNanos(1100)))
        store.pruneRxLogEntries(RADIO_A)
        val rows = store.fetchRxLogEntries(RADIO_A, 1200)
        assertEquals(1000, rows.size)
        assertEquals(1100u, rows.first().senderTimestamp)
        assertEquals(101u, rows.last().senderTimestamp)
    }

    @Test @OriginalCase("PersistenceStoreTests::Clearing RX log resets cached count for future pruning()")
    fun clearInvalidatesTheCountCacheAndCannotPruneANewReplacement() = runTest {
        for (i in 0..1100) store.saveRxLogEntry(rx(timestamp = i.toUInt(), receivedAt = AT.plusNanos(i.toLong())))
        store.pruneRxLogEntries(RADIO_A)
        store.clearRxLogEntries(RADIO_A)
        store.saveRxLogEntry(rx(timestamp = 42u))
        store.pruneRxLogEntries(RADIO_A)
        assertEquals(listOf(42u), store.fetchRxLogEntries(RADIO_A).map { it.senderTimestamp })
    }

    @Test @OriginalCase("PersistenceStoreTests::A store rebuilt over a populated container seeds its prune cache from disk()")
    fun reconnectSeedsRetentionFromTheRealCommittedDatabase() = runTest {
        for (i in 0 until 1100) {
            store.saveRxLogEntry(rx(timestamp = i.toUInt(), receivedAt = AT.plusNanos(i.toLong())))
            store.pruneRxLogEntries(RADIO_A)
        }
        assertEquals(1100, store.fetchRxLogEntries(RADIO_A, 1200).size)
        store.flushPendingRxLogEntries()
        val rebuilt = newStore()
        try {
            for (i in 1100 until 1202) {
                rebuilt.saveRxLogEntry(rx(timestamp = i.toUInt(), receivedAt = AT.plusNanos(i.toLong())))
                rebuilt.pruneRxLogEntries(RADIO_A)
            }
            assertEquals(1000, rebuilt.fetchRxLogEntries(RADIO_A, 1300).size)
        } finally {
            rebuilt.close()
        }
    }

    @Test @OriginalCase("PersistenceStoreTests::fetchRxLogEntries returns every matching channel row in receivedAt order()")
    fun correlatedReceptionHarvestRemainsOldestFirstAndPartitioned() = runTest {
        val earlier = rx(receivedAt = AT, timestamp = 42u).copy(pathNodes = Bytes.of(0xAA))
        val later = rx(receivedAt = AT.plusSeconds(2), timestamp = 42u).copy(pathNodes = Bytes.of(0xBB))
        for (row in listOf(later, earlier, rx(channelIndex = 2u), rx(timestamp = 43u), rx(RADIO_B))) store.saveRxLogEntry(row)
        assertEquals(listOf(Bytes.of(0xAA), Bytes.of(0xBB)), store.fetchRxLogEntries(RADIO_A, 1u, 42u).map { it.pathNodes })
    }

    @Test @OriginalCase("PersistenceStoreTests::batchSaveChannels rollback does not drop unsaved RxLog inserts()")
    fun aFailedChannelSyncCannotRollBackItsPriorDiagnosticBatch() = runTest {
        store.saveChannel(RADIO_A, ChannelInfo(1u, "Before", Bytes(ByteArray(16))))
        val entry = rx()
        store.saveRxLogEntry(entry)
        assertEquals(0L, store.rxInitiatedSaveCount)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_channel_update BEFORE UPDATE ON channels BEGIN SELECT RAISE(ABORT, 'source save failure'); END",
        )
        assertFailsWith<PersistenceStoreException> {
            store.batchSaveChannels(RADIO_A, SnapshotList.of(ChannelInfo(1u, "After", Bytes(ByteArray(16) { 0x42 }))),
                SnapshotList.empty(), null)
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_channel_update")
        assertEquals(entry.id, assertNotNull(store.findRxLogEntry(RADIO_A, 1u, 42u)).id)
        assertEquals(1, store.fetchRxLogEntries(RADIO_A, 40).size)
        assertNotNull(db.rxLogs().byId(RADIO_A.value, entry.id))
        assertEquals("Before", assertNotNull(store.fetchChannel(RADIO_A, 1u)).name)
    }

    @Test @OriginalCase("PersistenceStoreTests::batchUpdateRxLogRegion writes dual region fields()")
    @OriginalCase("PersistenceStoreTests::fetchEntriesWithTransportCode includes labeled rows and honors limit()")
    fun regionReprocessingKeepsExistingLabelsAndWritesNilVersusMultipleMatches() = runTest {
        for (i in 0..4) store.saveRxLogEntry(rx(timestamp = i.toUInt(), receivedAt = AT.plusSeconds(i.toLong()))
            .copy(transportCode = Bytes.of(i, 2), regionScope = if (i == 0) "Germany" else null))
        store.saveRxLogEntry(rx(timestamp = 99u, receivedAt = AT.plusSeconds(100)))
        assertEquals(listOf(4u, 3u), store.fetchEntriesWithTransportCode(RADIO_A, 2).map { it.senderTimestamp })
        val row = store.fetchRxLogEntries(RADIO_A).first { it.senderTimestamp == 0u }
        store.batchUpdateRxLogRegion(RADIO_A, SnapshotList.of(RxLogRegionUpdate(row.id, null, SnapshotList.of("de-by", "de-hh"))))
        val updated = store.fetchRxLogEntries(RADIO_A).first { it.id == row.id }
        assertNull(updated.regionScope)
        assertEquals(listOf("de-by", "de-hh"), updated.regionScopeMatches)
    }

    @Test @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion back-fills normal-case message via timestamp fallback()")
    @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion back-fills timestamp-corrected message()")
    @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion writes multi-match regionScopeMatches with nil scope()")
    @OriginalCase("PersistenceStoreTests::batchUpdateChannelMessageRegion skips outgoing messages()")
    fun channelRegionUpdatesUseTheOriginalWireClockAndNeverOutgoingRows() = runTest {
        val normal = message(contactID = null, channelIndex = 0u, timestamp = 1_703_111_111u, direction = MessageDirection.INCOMING)
        val corrected = message(contactID = null, channelIndex = 1u, timestamp = 1_704_000_000u, direction = MessageDirection.INCOMING)
            .copy(senderTimestamp = 1_703_222_222u)
        val multi = message(contactID = null, channelIndex = 3u, timestamp = 1_703_666_666u, direction = MessageDirection.INCOMING)
        val outgoing = message(contactID = null, channelIndex = 2u, timestamp = 1_703_333_333u)
        for (m in listOf(normal, corrected, multi, outgoing)) store.saveMessage(m)
        store.batchUpdateChannelMessageRegion(RADIO_A, SnapshotList.of(
            ChannelRegionUpdate(0u, 1_703_111_111u, "Germany", SnapshotList.of("Germany")),
            ChannelRegionUpdate(1u, 1_703_222_222u, "USA", SnapshotList.of("USA")),
            ChannelRegionUpdate(3u, 1_703_666_666u, null, SnapshotList.of("First", "Second")),
            ChannelRegionUpdate(2u, 1_703_333_333u, "France", SnapshotList.of("France"))))
        assertEquals("Germany", assertNotNull(store.fetchMessage(entity(id = normal.id))).regionScope)
        assertEquals("USA", assertNotNull(store.fetchMessage(entity(id = corrected.id))).regionScope)
        assertNull(assertNotNull(store.fetchMessage(entity(id = multi.id))).regionScope)
        assertEquals(listOf("First", "Second"), assertNotNull(store.fetchMessage(entity(id = multi.id))).regionScopeMatches)
        assertNull(assertNotNull(store.fetchMessage(entity(id = outgoing.id))).regionScope)
    }

    @Test @OriginalCase("PersistenceStoreTests::batchUpdateDMMessageRegion back-fills DM by sender prefix byte()")
    @OriginalCase("PersistenceStoreTests::batchUpdateDMMessageRegion ignores DMs from other senders at same timestamp()")
    fun directRegionUpdateDisambiguatesTheFirstPrefixByteAndPreservesPreviewBlobs() = runTest {
        val alice = message(timestamp = 1_703_444_444u, direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = Bytes.of(0xAA, 1, 2, 3, 4, 5))
        val bob = alice.copy(id = java.util.UUID.randomUUID(), senderKeyPrefix = Bytes.of(0xBB, 6, 7, 8, 9, 0))
        store.saveMessage(alice)
        store.saveMessage(bob)
        store.updateMessageLinkPreview(entity(id = alice.id), "https://example.invalid", "kept", Bytes.of(0x80), Bytes.of(0xFF), true)
        store.batchUpdateDMMessageRegion(RADIO_A, SnapshotList.of(
            DirectRegionUpdate(0xAAu, 1_703_444_444u, "Germany", SnapshotList.of("Germany"))))
        val updated = assertNotNull(store.fetchMessage(entity(id = alice.id)))
        assertEquals("Germany", updated.regionScope)
        assertEquals(listOf("Germany"), updated.regionScopeMatches)
        assertEquals(Bytes.of(0x80), updated.linkPreviewImageData)
        assertEquals(Bytes.of(0xFF), updated.linkPreviewIconData)
        assertTrue(updated.linkPreviewFetched)
        assertNull(assertNotNull(store.fetchMessage(entity(id = bob.id))).regionScope)
    }
}

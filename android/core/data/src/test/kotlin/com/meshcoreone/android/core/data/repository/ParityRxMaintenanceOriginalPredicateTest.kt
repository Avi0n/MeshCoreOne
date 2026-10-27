// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.RxLogRetention
import com.meshcoreone.android.core.database.RxLogEntryEntity
import com.meshcoreone.android.core.database.StoredInstant
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ParityRxMaintenanceOriginalPredicateTest : RepositoryTest() {
    private suspend fun radio(): RadioId {
        val id = UUID.randomUUID()
        val dto = DeviceDTO(
            id = id, radioId = RadioId(id), publicKey = Bytes(ByteArray(32) { 0x44 }),
            nodeName = "TestDevice", firmwareVersion = 8u, firmwareVersionString = "v1.11.0",
            manufacturerName = "TestMfg", buildDate = "01 Jan 2025",
            latitude = 37.7749, longitude = -122.4194, multiAcks = 0u, isActive = false,
        )
        store.saveDevice(dto)
        return dto.radioId
    }

    private fun originalRx(radio: RadioId, timestamp: UInt?) = RxLogEntryDTO.fromParsed(
        radio, ParsedRxLogData(
            snr = 10.5, rssi = -65, rawPayload = Bytes.of(0x15, 1, 2, 3),
            routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT,
            payloadVersion = 0u, payloadTypeBits = 5u, transportCode = null,
            pathLength = 1u, pathNodes = listOf(0x42u), packetPayload = Bytes.of(0xAB, 0xCD, 0xEF),
        ),
        receivedAt = Instant.now(), channelIndex = 1u, channelName = "TestChannel",
        decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = timestamp,
        regionScope = null, regionScopeMatches = SnapshotList.empty(), decodedText = "Hello mesh!",
    )

    @OriginalCase(
        "PersistenceStoreTests::RxLogEntryDTO(from:) falls back on out-of-range stored values instead of trapping()",
    )
    @Test fun originalSixCorruptValuesUsePinnedFallbacksAndRetainRawRowsThroughRepositoryReads() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val raw = RxLogEntryEntity(
            radio.value, UUID.randomUUID(), StoredInstant.from(Instant.now()), null, null,
            999, -1, 5000, null, 400, Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY,
            "deadbeef", 9999, null, DecryptStatus.NOT_APPLICABLE.rawValue, null, null,
            -10, null, SnapshotList.empty(), 0,
        )
        db.rxLogs().insert(raw)

        val valid = raw.copy(
            routeType = RouteType.FLOOD.rawValue.toLong(), payloadType = PayloadType.UNKNOWN.rawValue.toLong(),
            payloadVersion = 0, pathLength = 0, channelIndex = null, senderTimestamp = null,
        )
        val healthy = valid.copy(
            id = UUID.randomUUID(), receivedAt = StoredInstant.from(raw.receivedAt.toInstant().minusSeconds(1)),
            payloadVersion = 255, pathLength = 255, channelIndex = 255, senderTimestamp = 0xFFFF_FFFFL,
        )
        db.rxLogs().insert(healthy)
        repeat(2) {
            val entries = store.fetchRxLogEntries(radio)
            assertEquals(listOf(raw.id, healthy.id), entries.map { it.id })
            val dto = entries.first()
            assertEquals(radio, dto.radioId)
            assertEquals(RouteType.FLOOD, dto.routeType)
            assertEquals(PayloadType.UNKNOWN, dto.payloadType)
            assertEquals(0.toUByte(), dto.payloadVersion)
            assertEquals(0.toUByte(), dto.pathLength)
            kotlin.test.assertNull(dto.channelIndex)
            kotlin.test.assertNull(dto.senderTimestamp)
            assertEquals(Bytes.EMPTY, dto.pathNodes)
            assertEquals(Bytes.EMPTY, dto.packetPayload)
            assertEquals(Bytes.EMPTY, dto.rawPayload)
            assertEquals("deadbeef", dto.packetHash)
            assertEquals(255.toUByte(), entries.last().payloadVersion)
            assertEquals(255.toUByte(), entries.last().pathLength)
            assertEquals(255.toUByte(), entries.last().channelIndex)
            assertEquals(UInt.MAX_VALUE, entries.last().senderTimestamp)
            assertEquals(raw, assertNotNull(db.rxLogs().byId(radio.value, raw.id)))
            assertEquals(healthy, assertNotNull(db.rxLogs().byId(radio.value, healthy.id)))
            assertEquals(0, issues.size)
        }
        val isolated = listOf(
            valid.copy(routeType = 999),
            valid.copy(payloadType = -1),
            valid.copy(payloadVersion = 5000),
            valid.copy(pathLength = 400),
            valid.copy(channelIndex = 9999),
            valid.copy(senderTimestamp = -10),
        )
        for ((index, isolatedRow) in isolated.withIndex()) {
            val row = isolatedRow.copy(id = UUID.randomUUID())
            db.rxLogs().insert(row)
            repeat(2) {
                val entries = store.fetchRxLogEntries(radio)
                assertEquals(index + 3, entries.size)
                val dto = assertNotNull(entries.find { it.id == row.id })
                assertEquals(radio, dto.radioId)
                assertEquals(RouteType.FLOOD, dto.routeType)
                assertEquals(PayloadType.UNKNOWN, dto.payloadType)
                assertEquals(0.toUByte(), dto.payloadVersion)
                assertEquals(0.toUByte(), dto.pathLength)
                kotlin.test.assertNull(dto.channelIndex)
                kotlin.test.assertNull(dto.senderTimestamp)
                assertEquals(row, assertNotNull(db.rxLogs().byId(radio.value, row.id)))
                assertEquals(raw, assertNotNull(db.rxLogs().byId(radio.value, raw.id)))
                assertEquals(healthy, assertNotNull(db.rxLogs().byId(radio.value, healthy.id)))
                assertEquals(index.toLong() + 3, db.rxLogs().count(radio.value))
                assertEquals(0, issues.size)
            }
        }
    }

    @OriginalCase("PersistenceStoreTests::RX log prune is deferred until threshold is exceeded()")
    @Test fun originalStrict1100Then1101GateRetains1000AndExactFirst1100Last101() = runTest {
        val radio = radio()
        for (index in 0 until 1100) {
            store.saveRxLogEntry(originalRx(radio, index.toUInt()))
            store.pruneRxLogEntries(radio)
        }
        val before = store.fetchRxLogEntries(radio, 1200)
        assertEquals(1100, before.size)
        store.saveRxLogEntry(originalRx(radio, 1100u))
        store.pruneRxLogEntries(radio)

        val after = store.fetchRxLogEntries(radio, 1200)
        assertEquals(1000, after.size)
        assertEquals(1100u, after.firstOrNull()?.senderTimestamp)
        assertEquals(101u, after.lastOrNull()?.senderTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::Clearing RX log resets cached count for future pruning()")
    @Test fun originalClearAfter1101RowsRetainsOnly42AfterNextSaveAndPrune() = runTest {
        val radio = radio()
        for (index in 0 until 1101) store.saveRxLogEntry(originalRx(radio, index.toUInt()))
        store.pruneRxLogEntries(radio)
        store.clearRxLogEntries(radio)
        store.saveRxLogEntry(originalRx(radio, 42u))
        store.pruneRxLogEntries(radio)

        val entries = store.fetchRxLogEntries(radio)
        assertEquals(1, entries.size)
        assertEquals(42u, entries.firstOrNull()?.senderTimestamp)
    }

    @OriginalCase("PersistenceStoreTests::A store rebuilt over a populated container seeds its prune cache from disk()")
    @Test fun originalColdSecondStoreOverSameDatabaseRetains1100Then1202CycleTo1000() = runTest {
        val radio = radio()
        for (index in 0 until 1100) {
            store.saveRxLogEntry(originalRx(radio, index.toUInt()))
            store.pruneRxLogEntries(radio)
        }
        assertEquals(1100, store.fetchRxLogEntries(radio, 1200).size)
        val storeB = newStore()
        try {
            for (index in 1100 until 1202) {
                storeB.saveRxLogEntry(originalRx(radio, index.toUInt()))
                storeB.pruneRxLogEntries(radio)
            }
            assertEquals(1000, storeB.fetchRxLogEntries(radio, 1300).size)
        } finally {
            storeB.close()
        }
    }

    @OriginalCase("PersistenceStoreTests::saveRxLogEntry commits once per batchSize inserts()")
    @Test fun originalExactlyOneBatchRetainsFetchedBatchSizeAndInitiatedSaveOne() = runTest {
        val radio = radio()
        assertEquals(20L, RxLogRetention.BATCH_SIZE)
        for (index in 0 until RxLogRetention.BATCH_SIZE) {
            store.saveRxLogEntry(originalRx(radio, index.toUInt()))
        }

        assertEquals(RxLogRetention.BATCH_SIZE.toInt(), store.fetchRxLogEntries(radio, 40).size)
        assertEquals(1L, store.rxInitiatedSaveCount)
    }

    @OriginalCase("PersistenceStoreTests::findRxLogEntry sees an insert before the batch save()")
    @Test fun originalQueued42ChannelOneRetainsZeroSavesOptionalMatchingIdAndExplicitFinalFlush() = runTest {
        val radio = radio()
        val dto = originalRx(radio, 42u)
        store.saveRxLogEntry(dto)

        assertEquals(0L, store.rxInitiatedSaveCount)
        val found = store.findRxLogEntry(radio, 1u, 42u)
        assertEquals(dto.id, found?.id)
        store.flushPendingRxLogEntries()
    }

    @OriginalCase("PersistenceStoreTests::batchSaveChannels rollback does not drop unsaved RxLog inserts()")
    @Test fun originalNewChannelInsertFailureRetainsQueued42ChannelOneZeroBeforeMatchingIdAndOneDiagnosticRow() = runTest {
        val radio = radio()
        val dto = originalRx(radio, 42u)
        store.saveRxLogEntry(dto)
        assertEquals(0L, store.rxInitiatedSaveCount)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_original_channel_insert AFTER INSERT ON channels BEGIN SELECT RAISE(FAIL, 'test'); END",
        )
        try {
            assertFailsWith<PersistenceStoreException> {
                store.batchSaveChannels(
                    radio, SnapshotList.of(ChannelInfo(1u, "Test", Bytes(ByteArray(16) { 0x42 }))),
                    SnapshotList.empty(), null,
                )
            }
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_original_channel_insert")
        }

        assertEquals(0, store.fetchChannels(radio).size)
        assertEquals(dto.id, store.findRxLogEntry(radio, 1u, 42u)?.id)
        assertEquals(1, store.fetchRxLogEntries(radio, 40).size)
        store.flushPendingRxLogEntries()
    }

    @OriginalCase("PersistenceStoreTests::flushPendingRxLogEntries commits a partial batch()")
    @Test fun originalFreshSingleQueuedRowRetainsZeroThenOneSavesAndSameDatabaseSecondStoreOneRow() = runTest {
        val radio = radio()
        store.saveRxLogEntry(originalRx(radio, 1u))
        assertEquals(0L, store.rxInitiatedSaveCount)
        store.flushPendingRxLogEntries()
        assertEquals(1L, store.rxInitiatedSaveCount)
        val storeB = newStore()
        try {
            assertEquals(1, storeB.fetchRxLogEntries(radio).size)
        } finally {
            storeB.close()
        }
    }
}

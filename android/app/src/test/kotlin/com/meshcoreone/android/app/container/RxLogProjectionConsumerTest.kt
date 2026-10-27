// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.database.RxLogEntryEntity
import com.meshcoreone.android.core.database.StoredInstant
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.feature.tools.diagnostics.rxlog.RxLogStateHolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RxLogProjectionConsumerTest : RoomProcessTest() {
    @OriginalCase("PersistenceStoreTests::RxLogEntryDTO(from:) falls back on out-of-range stored values instead of trapping()")
    @Test fun sixStoredFallbacksReachRealServiceAndFeatureFeedWithoutDroppingOrRewritingEitherRow() = runTest {
        val h = ContainerHarness(this, store)
        val holder = RxLogStateHolder(backgroundScope)
        try {
            h.container.foreground.activityStarted()
            h.links.configureRadio = { radio ->
                radio.respond = { command ->
                    if (command[0].toInt() == 0x1f) {
                        listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                    } else null
                }
            }
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            assertEquals(SyncState.Synced, assertNotNull(h.sessions.current).syncCoordinator.state)
            assertEquals((0..7).toList(), h.links.radios.single().sent
                .filter { it[0].toInt() == 0x1f }.map { it[1].toInt() })
            val radio = assertNotNull(h.appState.currentRadioId)
            val raw = RxLogEntryEntity(
                radio.value, UUID(0, 100), StoredInstant.from(EPOCH.plusSeconds(1)), null, null,
                999, -1, 5000, null, 400, Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY,
                "deadbeef", 9999, null, DecryptStatus.NOT_APPLICABLE.rawValue, null, null,
                -10, null, SnapshotList.empty(), 0,
            )
            val healthy = raw.copy(
                id = UUID(0, 101), receivedAt = StoredInstant.from(EPOCH), routeType = 3,
                payloadType = 255, payloadVersion = 255, pathLength = 255,
                channelIndex = 255, senderTimestamp = 0xFFFF_FFFFL, packetHash = "healthy-packet",
            )
            val other = healthy.copy(radioId = UUID(0, 102), id = raw.id)
            db.rxLogs().insert(raw)
            db.rxLogs().insert(healthy)
            db.rxLogs().insert(other)
            val service = assertNotNull(h.sessions.current).rxLogService
            val dependencies = h.container.createToolsDiagnosticsDependencies().rxLog
            val feed = assertNotNull(dependencies.rxLogService())
            holder.configure(dependencies)

            repeat(2) {
                for (entries in listOf(
                    store.fetchRxLogEntries(radio), service.loadExistingEntries(), feed.loadExistingEntries(),
                )) {
                    assertEquals(listOf(raw.id, healthy.id), entries.map { it.id })
                    assertOriginalFallback(entries.first())
                    val projectedHealthy = entries.last()
                    assertEquals(RouteType.TC_DIRECT, projectedHealthy.routeType)
                    assertEquals(PayloadType.UNKNOWN, projectedHealthy.payloadType)
                    assertEquals(255.toUByte(), projectedHealthy.payloadVersion)
                    assertEquals(255.toUByte(), projectedHealthy.pathLength)
                    assertEquals(255.toUByte(), projectedHealthy.channelIndex)
                    assertEquals(UInt.MAX_VALUE, projectedHealthy.senderTimestamp)
                }
                holder.subscribe()
                assertEquals(listOf(raw.id, healthy.id), holder.state.value.entries.map { it.id })
                assertOriginalFallback(holder.state.value.entries.first())
                assertEquals(mapOf("deadbeef" to 1, "healthy-packet" to 1), holder.state.value.groupCounts)
                assertEquals(raw, assertNotNull(db.rxLogs().byId(radio.value, raw.id)))
                assertEquals(healthy, assertNotNull(db.rxLogs().byId(radio.value, healthy.id)))
                assertEquals(other, assertNotNull(db.rxLogs().byId(other.radioId, other.id)))
                assertEquals(2L, db.rxLogs().count(radio.value))
                assertEquals(1L, db.rxLogs().count(other.radioId))
            }
        } finally {
            holder.unsubscribe()
            h.container.close()
        }
    }

    private fun assertOriginalFallback(dto: RxLogEntryDTO) {
        assertEquals(RouteType.FLOOD, dto.routeType)
        assertEquals(PayloadType.UNKNOWN, dto.payloadType)
        assertEquals(0.toUByte(), dto.payloadVersion)
        assertEquals(0.toUByte(), dto.pathLength)
        assertNull(dto.channelIndex)
        assertNull(dto.senderTimestamp)
        assertEquals(Bytes.EMPTY, dto.pathNodes)
        assertEquals(Bytes.EMPTY, dto.packetPayload)
        assertEquals(Bytes.EMPTY, dto.rawPayload)
        assertEquals("deadbeef", dto.packetHash)
    }
}

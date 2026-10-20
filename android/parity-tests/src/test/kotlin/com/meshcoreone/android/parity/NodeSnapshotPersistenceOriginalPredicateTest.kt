// PortedFrom: MC1Services/Tests/MC1ServicesTests/NodeSnapshotServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: actual Room transactions replace SwiftData; injected time preserves source ordering without sleeps.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeStatusMetrics
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.remote.NodeSnapshotService
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NodeSnapshotPersistenceOriginalPredicateTest : RoomProcessTest() {
    private val publicKey = Bytes(ByteArray(32) { 0x42 })
    private val now = Instant.ofEpochSecond(1_704_067_200)
    private val prefix = Bytes.of(0x01, 0x02, 0x03, 0x04)

    private class SnapshotClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    private inner class Fixture(scope: CoroutineScope) {
        val clock = SnapshotClock(now)
        val persistence = RoomPersistenceStore(db, scope, clock)
        val service = NodeSnapshotService(persistence, clock)

        suspend fun seed(
            secondsAgo: Long, battery: Int, snr: Double? = null, rssi: Short? = null,
            noise: Short? = null, uptime: UInt? = null,
        ): UUID {
            clock.now = now.minusSeconds(secondsAgo)
            try {
                return persistence.saveNodeStatusSnapshot(publicKey, NodeStatusMetrics(
                    batteryMillivolts = battery.toUShort(), lastSNR = snr, lastRSSI = rssi,
                    noiseFloor = noise, uptimeSeconds = uptime,
                ))
            } finally {
                clock.now = now
            }
        }

        suspend fun priorNeighbor(): UUID {
            val id = seed(3600, 3700, 6.0, -90, -120, 3600u)
            persistence.updateSnapshotNeighbors(id, SnapshotList.of(NeighborSnapshotEntry(prefix, 6.0, 30)))
            return id
        }
    }

    private suspend fun TestScope.withSnapshots(assertions: suspend Fixture.() -> Unit) {
        val fixture = Fixture(backgroundScope)
        try {
            fixture.assertions()
        } finally {
            fixture.persistence.close()
        }
    }

    private fun metrics(battery: Int, uptime: UInt) = NodeStatusMetrics(
        batteryMillivolts = battery.toUShort(), lastSNR = 8.5, lastRSSI = -87,
        noiseFloor = -120, uptimeSeconds = uptime, rxAirtimeSeconds = 100u,
        packetsSent = 500u, packetsReceived = 1000u,
    )

    @OriginalCase("NodeSnapshotServiceTests::Second status within the window enriches the same row, not a new one()")
    @Test fun originalSecondStatusRetainsTheFirstIdBatteryAndUptime() = runTest {
        withSnapshots {
            val first = assertNotNull(service.recordSnapshot(publicKey, status = metrics(3850, 3600u)))
            val second = service.recordSnapshot(publicKey, status = metrics(3900, 7200u))
            assertEquals(first, second)
            val rows = service.fetchSnapshots(publicKey)
            assertEquals(1, rows.size)
            assertEquals(3850.toUShort(), rows[0].batteryMillivolts)
            assertEquals(3600u, rows[0].uptimeSeconds)
            assertEquals(first, rows[0].id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Different nodes get independent snapshots()")
    @Test fun originalFortyTwoAndNinetyNineNodesCommitIndependentSnapshots() = runTest {
        withSnapshots {
            val otherKey = Bytes(ByteArray(32) { 0x99.toByte() })
            val first = assertNotNull(service.recordSnapshot(publicKey, status = metrics(3850, 3600u)))
            val second = assertNotNull(service.recordSnapshot(otherKey, status = metrics(3700, 1800u)))
            assertNotEquals(first, second)
            val firstRow = service.fetchSnapshots(publicKey).single()
            val secondRow = service.fetchSnapshots(otherKey).single()
            assertEquals(first, firstRow.id)
            assertEquals(second, secondRow.id)
            assertEquals(publicKey, firstRow.nodePublicKey)
            assertEquals(otherKey, secondRow.nodePublicKey)
            assertEquals(3850.toUShort(), firstRow.batteryMillivolts)
            assertEquals(3700.toUShort(), secondRow.batteryMillivolts)
            assertEquals(1800u, secondRow.uptimeSeconds)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Neighbors enrich the in-window snapshot()")
    @Test fun originalNeighborEnrichmentKeepsTheActualStatusRow() = runTest {
        withSnapshots {
            val first = assertNotNull(service.recordSnapshot(publicKey, status = metrics(3850, 3600u)))
            val neighbors = SnapshotList.of(NeighborSnapshotEntry(prefix, 5.5, 30))
            assertEquals(first, service.recordSnapshot(publicKey, neighbors = neighbors))
            val row = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertEquals(1, row.neighborSnapshots?.size)
            assertEquals(5.5, row.neighborSnapshots?.first()?.snr)
            assertEquals(neighbors, row.neighborSnapshots)
            assertEquals(first, row.id)
            assertEquals(3600u, row.uptimeSeconds)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Telemetry enriches the in-window snapshot()")
    @Test fun originalTemperatureEnrichmentKeepsTheActualStatusRow() = runTest {
        withSnapshots {
            val first = assertNotNull(service.recordSnapshot(publicKey, status = metrics(3850, 3600u)))
            val telemetry = SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 32.5))
            assertEquals(first, service.recordSnapshot(publicKey, telemetry = telemetry))
            val row = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertEquals(1, row.telemetryEntries?.size)
            assertEquals(32.5, row.telemetryEntries?.first()?.value)
            assertEquals(telemetry, row.telemetryEntries)
            assertEquals(first, row.id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Fetch snapshots returns ascending order()")
    @Test fun originalTwentyAndTenSecondRowsReturnInAscendingOrder() = runTest {
        withSnapshots {
            val first = seed(20, 3600)
            val second = seed(10, 3800)
            val rows = service.fetchSnapshots(publicKey)
            assertEquals(2, rows.size)
            assertEquals(3600.toUShort(), rows[0].batteryMillivolts)
            assertEquals(3800.toUShort(), rows[1].batteryMillivolts)
            assertEquals(listOf(first, second), rows.map { it.id })
            assertEquals(listOf(now.minusSeconds(20), now.minusSeconds(10)), rows.map { it.timestamp })
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Prune only deletes snapshots older than cutoff()")
    @Test fun originalThirtySecondPruneRetainsOnlyTheTenSecondRow() = runTest {
        withSnapshots {
            seed(60, 3600)
            val recent = seed(10, 3800)
            assertEquals(2, service.fetchSnapshots(publicKey).size)
            service.pruneOldSnapshots(now.minusSeconds(30))
            val remaining = service.fetchSnapshots(publicKey)
            assertEquals(1, remaining.size)
            assertEquals(recent, remaining.first().id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Prune with future cutoff does not delete recent snapshots()")
    @Test fun originalOneCalendarYearAgoCutoffPreservesTheRecentRow() = runTest {
        withSnapshots {
            val recent = seed(0, 3850)
            service.pruneOldSnapshots(now.atZone(ZoneOffset.UTC).minusYears(1).toInstant())
            val remaining = service.fetchSnapshots(publicKey)
            assertEquals(1, remaining.size)
            assertEquals(recent, remaining.single().id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Fetch snapshots with since filter()")
    @Test fun originalFifteenSecondSinceFilterReturnsOnlyTheFiveSecondRow() = runTest {
        withSnapshots {
            seed(30, 3600)
            val recent = seed(5, 3800)
            assertEquals(2, service.fetchSnapshots(publicKey).size)
            val rows = service.fetchSnapshots(publicKey, since = now.minusSeconds(15))
            assertEquals(1, rows.size)
            assertEquals(3800.toUShort(), rows[0].batteryMillivolts)
            assertEquals(recent, rows[0].id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Enrichment data survives save -> enrich -> fetchAll round-trip()")
    @Test fun originalTwoRowEnrichmentRoundTripRetainsEveryHistoryEntry() = runTest {
        withSnapshots {
            val first = seed(20, 3600, 7.0, -90, -120)
            val second = seed(10, 3800, 8.5, -85, -118)
            val firstTelemetry = SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 25.0))
            val secondTelemetry = SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 30.0))
            val firstNeighbors = SnapshotList.of(NeighborSnapshotEntry(prefix, 5.0, 60))
            val secondNeighbors = SnapshotList.of(NeighborSnapshotEntry(Bytes.of(5, 6, 7, 8), 9.0, 10))
            persistence.updateSnapshotTelemetry(first, firstTelemetry)
            persistence.updateSnapshotTelemetry(second, secondTelemetry)
            persistence.updateSnapshotNeighbors(first, firstNeighbors)
            persistence.updateSnapshotNeighbors(second, secondNeighbors)
            val rows = service.fetchSnapshots(publicKey)
            assertEquals(2, rows.size)
            assertEquals(1, rows[0].telemetryEntries?.size)
            assertEquals(25.0, rows[0].telemetryEntries?.first()?.value)
            assertEquals(1, rows[0].neighborSnapshots?.size)
            assertEquals(5.0, rows[0].neighborSnapshots?.first()?.snr)
            assertEquals(1, rows[1].telemetryEntries?.size)
            assertEquals(30.0, rows[1].telemetryEntries?.first()?.value)
            assertEquals(1, rows[1].neighborSnapshots?.size)
            assertEquals(9.0, rows[1].neighborSnapshots?.first()?.snr)
            assertEquals(listOf(first, second), rows.map { it.id })
            assertEquals(firstTelemetry, rows[0].telemetryEntries)
            assertEquals(secondTelemetry, rows[1].telemetryEntries)
            assertEquals(firstNeighbors, rows[0].neighborSnapshots)
            assertEquals(secondNeighbors, rows[1].neighborSnapshots)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Status + telemetry + neighbors in one window collapse onto a single row()")
    @Test fun originalStatusTelemetryAndNeighborsCollapseOntoOneRealRow() = runTest {
        withSnapshots {
            val first = assertNotNull(service.recordSnapshot(publicKey, status = metrics(3700, 3600u)))
            val telemetry = SnapshotList.of(
                TelemetrySnapshotEntry(0, "temperature", 28.5), TelemetrySnapshotEntry(1, "humidity", 65.0),
            )
            val neighbors = SnapshotList.of(NeighborSnapshotEntry(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), 6.5, 45))
            assertEquals(first, service.recordSnapshot(publicKey, telemetry = telemetry, neighbors = neighbors))
            val rows = service.fetchSnapshots(publicKey)
            assertEquals(1, rows.size)
            assertEquals(2, rows[0].telemetryEntries?.size)
            assertEquals(1, rows[0].neighborSnapshots?.size)
            assertEquals(first, rows[0].id)
            assertEquals(telemetry, rows[0].telemetryEntries)
            assertEquals(neighbors, rows[0].neighborSnapshots)
            assertEquals(3700.toUShort(), rows[0].batteryMillivolts)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Telemetry-first then status-within-window backfills status onto the single snapshot()")
    @Test fun originalTelemetryFirstCaptureReceivesEveryOriginalStatusBackfill() = runTest {
        withSnapshots {
            val telemetry = SnapshotList.of(TelemetrySnapshotEntry(1, "temperature", 21.5))
            val first = assertNotNull(service.recordSnapshot(publicKey, telemetry = telemetry))
            assertNull(assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey)).uptimeSeconds)
            val status = NodeStatusMetrics(
                batteryMillivolts = 3900u, lastSNR = 9.0, lastRSSI = -84, noiseFloor = -119,
                uptimeSeconds = 7200u, rxAirtimeSeconds = 150u, packetsSent = 600u,
                packetsReceived = 1200u, receiveErrors = 3u,
            )
            assertEquals(first, service.recordSnapshot(publicKey, status = status))
            val rows = service.fetchSnapshots(publicKey)
            assertEquals(1, rows.size)
            assertEquals(21.5, rows[0].telemetryEntries?.first()?.value)
            assertEquals(7200u, rows[0].uptimeSeconds)
            assertEquals(3900.toUShort(), rows[0].batteryMillivolts)
            assertEquals(3u, rows[0].receiveErrors)
            assertEquals(telemetry, rows[0].telemetryEntries)
            assertEquals(first, rows[0].id)
            assertEquals(9.0, rows[0].lastSNR)
            assertEquals((-84).toShort(), rows[0].lastRSSI)
            assertEquals((-119).toShort(), rows[0].noiseFloor)
            assertEquals(150u, rows[0].rxAirtimeSeconds)
            assertEquals(600u, rows[0].packetsSent)
            assertEquals(1200u, rows[0].packetsReceived)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Neighbors captured before any status persist on a fresh snapshot()")
    @Test fun originalNeighborOnlyCaptureCommitsWithoutInventingStatus() = runTest {
        withSnapshots {
            val neighbors = SnapshotList.of(NeighborSnapshotEntry(Bytes.of(0x0A, 0x0B, 0x0C, 0x0D), 4.0, 90))
            val id = assertNotNull(service.recordSnapshot(publicKey, neighbors = neighbors))
            val rows = service.fetchSnapshots(publicKey)
            assertEquals(1, rows.size)
            assertEquals(1, rows[0].neighborSnapshots?.size)
            assertNull(rows[0].uptimeSeconds)
            assertEquals(neighbors, rows[0].neighborSnapshots)
            assertEquals(id, rows[0].id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous neighbor snapshot skips a more recent status-only row()")
    @Test fun originalNeighborBaselineSkipsTheNewerStatusOnlyRow() = runTest {
        withSnapshots {
            val prior = priorNeighbor()
            val recent = seed(1800, 3850, 8.0, -85, -118, 7200u)
            assertEquals(2, service.fetchSnapshots(publicKey).size)
            val previous = assertNotNull(service.neighborBaseline(publicKey).previous)
            assertEquals(6.0, previous.neighborSnapshots?.first()?.snr)
            assertEquals(prior, previous.id)
            assertNotEquals(recent, previous.id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous neighbor snapshot excludes the current in-window capture()")
    @Test fun originalNeighborBaselineExcludesTheCurrentNineSnrCapture() = runTest {
        withSnapshots {
            val prior = priorNeighbor()
            val current = assertNotNull(service.recordSnapshot(
                publicKey, neighbors = SnapshotList.of(NeighborSnapshotEntry(prefix, 9.0, 5)),
            ))
            assertNotEquals(prior, current)
            val previous = assertNotNull(service.neighborBaseline(publicKey).previous)
            assertEquals(6.0, previous.neighborSnapshots?.first()?.snr)
            assertEquals(prior, previous.id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous neighbor snapshot is nil when no prior neighbor data exists()")
    @Test fun originalStatusOnlyHistoryHasNoNeighborBaseline() = runTest {
        withSnapshots {
            val id = assertNotNull(service.recordSnapshot(publicKey, status = metrics(3850, 3600u)))
            assertEquals(id, service.fetchSnapshots(publicKey).single().id)
            assertNull(service.neighborBaseline(publicKey).previous)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous neighbor snapshot returns an out-of-window neighbor row as the baseline()")
    @Test fun originalOutOfWindowNeighborRowIsItsOwnLatestBaseline() = runTest {
        withSnapshots {
            val prior = priorNeighbor()
            val previous = assertNotNull(service.neighborBaseline(publicKey).previous)
            assertEquals(6.0, previous.neighborSnapshots?.first()?.snr)
            assertEquals(prior, previous.id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous neighbor snapshot is nil when the only neighbor row is the in-window capture()")
    @Test fun originalOnlyCurrentNeighborCaptureHasNoPreviousBaseline() = runTest {
        withSnapshots {
            val id = assertNotNull(service.recordSnapshot(
                publicKey, neighbors = SnapshotList.of(NeighborSnapshotEntry(prefix, 9.0, 5)),
            ))
            assertEquals(id, service.fetchSnapshots(publicKey).single().id)
            assertNull(service.neighborBaseline(publicKey).previous)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Seen neighbor prefixes span all history, not just the previous snapshot()")
    @Test fun originalSeenPrefixesRetainSilentAAndExcludeCurrentC() = runTest {
        withSnapshots {
            fun prefix(seed: Int) = Bytes(ByteArray(6) { (seed + it).toByte() })
            val a = prefix(0xA0)
            val b = prefix(0xB0)
            val c = prefix(0xC0)
            val older = seed(7200, 3700, 6.0, -90, -120, 3600u)
            persistence.updateSnapshotNeighbors(older, SnapshotList.of(NeighborSnapshotEntry(a, 6.0, 30)))
            val recent = seed(3600, 3800, 7.0, -88, -119, 5400u)
            persistence.updateSnapshotNeighbors(recent, SnapshotList.of(NeighborSnapshotEntry(b, 7.0, 30)))
            val baseline = service.neighborBaseline(publicKey)
            val previous = assertNotNull(baseline.previous)
            assertEquals(b, previous.neighborSnapshots?.first()?.publicKeyPrefix)
            assertFalse(previous.neighborSnapshots.orEmpty().any { it.publicKeyPrefix == a })
            assertTrue(a in baseline.seenPrefixes)
            assertTrue(b in baseline.seenPrefixes)
            val current = assertNotNull(service.recordSnapshot(
                publicKey, neighbors = SnapshotList.of(NeighborSnapshotEntry(c, 9.0, 5)),
            ))
            val after = service.neighborBaseline(publicKey)
            assertTrue(a in after.seenPrefixes)
            assertFalse(c in after.seenPrefixes)
            assertEquals(setOf(a, b), after.seenPrefixes)
            assertEquals(recent, assertNotNull(after.previous).id)
            assertEquals(current, service.fetchSnapshots(publicKey).last().id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous status snapshot skips a more recent neighbor-only row()")
    @Test fun originalStatusBaselineSkipsTheNewerNeighborOnlyCapture() = runTest {
        withSnapshots {
            val prior = seed(3600, 3700, 6.0, -90, -120, 3600u)
            assertNotNull(service.recordSnapshot(
                publicKey, neighbors = SnapshotList.of(NeighborSnapshotEntry(prefix, 9.0, 5)),
            ))
            val previous = assertNotNull(service.previousStatusSnapshot(publicKey, now.plusNanos(1)))
            assertEquals(3600u, previous.uptimeSeconds)
            assertEquals(prior, previous.id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous status snapshot includes an in-window status capture()")
    @Test fun originalStatusBaselineIncludesTheCurrentStatusRow() = runTest {
        withSnapshots {
            val id = assertNotNull(service.recordSnapshot(publicKey, status = metrics(3850, 3600u)))
            val previous = assertNotNull(service.previousStatusSnapshot(publicKey, now.plusNanos(1)))
            assertEquals(3600u, previous.uptimeSeconds)
            assertEquals(id, previous.id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Previous status snapshot is nil when only neighbor data exists()")
    @Test fun originalNeighborOnlyHistoryHasNoStatusBaseline() = runTest {
        withSnapshots {
            val id = assertNotNull(service.recordSnapshot(
                publicKey, neighbors = SnapshotList.of(NeighborSnapshotEntry(prefix, 9.0, 5)),
            ))
            assertEquals(id, service.fetchSnapshots(publicKey).single().id)
            assertNull(service.previousStatusSnapshot(publicKey, now.plusNanos(1)))
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::Concurrent in-window captures never duplicate a snapshot()")
    @Test fun originalConcurrentStatusAndTelemetryResolveToOneCommittedRow() = runTest {
        withSnapshots {
            val ready = CompletableDeferred<Unit>()
            val telemetry = SnapshotList.of(TelemetrySnapshotEntry(0, "temperature", 19.0))
            val statusResult = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                ready.await()
                service.recordSnapshot(publicKey, status = metrics(3850, 3600u))
            }
            val telemetryResult = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                ready.await()
                service.recordSnapshot(publicKey, telemetry = telemetry)
            }
            ready.complete(Unit)
            val statusId = assertNotNull(statusResult.await())
            val telemetryId = assertNotNull(telemetryResult.await())
            assertEquals(statusId, telemetryId)
            val rows = service.fetchSnapshots(publicKey)
            assertEquals(1, rows.size)
            assertEquals(statusId, rows[0].id)
            assertEquals(telemetry, rows[0].telemetryEntries)
            assertEquals(3850.toUShort(), rows[0].batteryMillivolts)
            assertEquals(3600u, rows[0].uptimeSeconds)
        }
    }
}

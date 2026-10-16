package one.meshcore.shared.remotenode

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `NodeSnapshotServiceTests`: the enrich-vs-insert window, the neighbour/status delta
 * baselines, and the metrics mapping. A controllable clock pins the 15-minute window.
 */
class NodeSnapshotServiceTest {
    private val nodeKey = testPublicKey(0x42)
    private val base = 10_000.0
    private val pastWindow = NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS + 1.0

    private fun metrics(battery: Int, uptime: Long?): NodeStatusMetrics = NodeStatusMetrics(
        batteryMillivolts = battery,
        lastSNR = 8.5,
        lastRSSI = -87,
        noiseFloor = -120,
        uptimeSeconds = uptime,
        rxAirtimeSeconds = 100,
        packetsSent = 500,
        packetsReceived = 1000,
        receiveErrors = null,
        sentDirect = null,
        sentFlood = null,
        receivedDirect = null,
        receivedFlood = null,
        directDuplicates = null,
        floodDuplicates = null,
    )

    private fun neighborPrefix(seed: Int): ByteArray = ByteArray(6) { (seed + it).toByte() }

    @Test
    fun recordReturnsIdOnFirstCapture() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        val id = service.recordSnapshot(nodeKey, status = metrics(3850, 3600))
        assertTrue(service.fetchSnapshots(nodeKey).any { it.id == id })
    }

    @Test
    fun secondStatusWithinWindowEnrichesSameRow() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        val first = service.recordSnapshot(nodeKey, status = metrics(3850, 3600))
        val second = service.recordSnapshot(nodeKey, status = metrics(3900, 7200))

        assertEquals(first, second)
        val snapshots = service.fetchSnapshots(nodeKey)
        assertEquals(1, snapshots.size)
        // A row already carrying status is not overwritten within the window.
        assertEquals(3850, snapshots[0].batteryMillivolts)
        assertEquals(3600L, snapshots[0].uptimeSeconds)
    }

    @Test
    fun differentNodesAreIndependent() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        val a = service.recordSnapshot(nodeKey, status = metrics(3850, 3600))
        val b = service.recordSnapshot(testPublicKey(0x99), status = metrics(3700, 1800))
        assertNotEquals(a, b)
    }

    @Test
    fun telemetryFirstThenStatusBackfillsSameRow() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        val telemetryID = service.recordSnapshot(
            nodeKey,
            telemetry = listOf(TelemetrySnapshotEntry(channel = 1, type = "temperature", value = 21.5)),
        )
        val statusID = service.recordSnapshot(nodeKey, status = metrics(3900, 7200))

        assertEquals(telemetryID, statusID)
        val snapshots = service.fetchSnapshots(nodeKey)
        assertEquals(1, snapshots.size)
        assertEquals(21.5, snapshots[0].telemetryEntries?.first()?.value)
        assertEquals(7200L, snapshots[0].uptimeSeconds)
        assertEquals(3900, snapshots[0].batteryMillivolts)
    }

    @Test
    fun neighborsBeforeStatusPersistOnFreshRowWithNoStatus() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        service.recordSnapshot(
            nodeKey,
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0x0A), snr = 4.0, secondsAgo = 90)),
        )
        val snapshots = service.fetchSnapshots(nodeKey)
        assertEquals(1, snapshots.size)
        assertEquals(1, snapshots[0].neighborSnapshots?.size)
        assertNull(snapshots[0].uptimeSeconds)
    }

    @Test
    fun statusTelemetryNeighborsCollapseOntoOneRow() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        val statusID = service.recordSnapshot(nodeKey, status = metrics(3700, 3600))
        val enrichedID = service.recordSnapshot(
            nodeKey,
            telemetry = listOf(
                TelemetrySnapshotEntry(0, "temperature", 28.5),
                TelemetrySnapshotEntry(1, "humidity", 65.0),
            ),
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0xAA), snr = 6.5, secondsAgo = 45)),
        )
        assertEquals(statusID, enrichedID)
        val snapshots = service.fetchSnapshots(nodeKey)
        assertEquals(1, snapshots.size)
        assertEquals(2, snapshots[0].telemetryEntries?.size)
        assertEquals(1, snapshots[0].neighborSnapshots?.size)
    }

    @Test
    fun capturesPastWindowInsertNewRowsInAscendingOrder() = runTest {
        var clock = base
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        service.recordSnapshot(nodeKey, status = metrics(3600, 3600))
        clock += pastWindow
        service.recordSnapshot(nodeKey, status = metrics(3800, 7200))

        val snapshots = service.fetchSnapshots(nodeKey)
        assertEquals(2, snapshots.size)
        assertEquals(3600, snapshots[0].batteryMillivolts)
        assertEquals(3800, snapshots[1].batteryMillivolts)
    }

    @Test
    fun sinceFilterExcludesOlderSnapshots() = runTest {
        var clock = base
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        service.recordSnapshot(nodeKey, status = metrics(3600, 3600))
        clock += pastWindow
        val cutoff = clock
        service.recordSnapshot(nodeKey, status = metrics(3800, 7200))

        val filtered = service.fetchSnapshots(nodeKey, since = cutoff)
        assertEquals(1, filtered.size)
        assertEquals(3800, filtered[0].batteryMillivolts)
    }

    @Test
    fun pruneDeletesOnlySnapshotsOlderThanCutoff() = runTest {
        var clock = base
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        service.recordSnapshot(nodeKey, status = metrics(3600, 3600))
        clock += pastWindow
        val recent = service.recordSnapshot(nodeKey, status = metrics(3800, 7200))

        service.pruneOldSnapshots(cutoff = clock - 1.0)

        val remaining = service.fetchSnapshots(nodeKey)
        assertEquals(1, remaining.size)
        assertEquals(recent, remaining[0].id)
    }

    // MARK: Neighbour baseline

    @Test
    fun neighborBaselineSkipsNewerStatusOnlyRow() = runTest {
        var clock = base
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        service.recordSnapshot(
            nodeKey,
            status = metrics(3700, 3600),
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0x01), snr = 6.0, secondsAgo = 30)),
        )
        clock += pastWindow
        service.recordSnapshot(nodeKey, status = metrics(3850, 7200))

        val previous = service.neighborBaseline(nodeKey).previous
        assertEquals(6.0, previous?.neighborSnapshots?.first()?.snr)
    }

    @Test
    fun neighborBaselineExcludesTheInWindowCapture() = runTest {
        var clock = base
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        service.recordSnapshot(
            nodeKey,
            status = metrics(3700, 3600),
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0x01), snr = 6.0, secondsAgo = 30)),
        )
        clock += pastWindow
        service.recordSnapshot(
            nodeKey,
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0x01), snr = 9.0, secondsAgo = 5)),
        )

        val previous = service.neighborBaseline(nodeKey).previous
        assertEquals(6.0, previous?.neighborSnapshots?.first()?.snr)
    }

    @Test
    fun neighborBaselineIsNullWhenOnlyInWindowNeighborExists() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        service.recordSnapshot(
            nodeKey,
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0x01), snr = 9.0, secondsAgo = 5)),
        )
        assertNull(service.neighborBaseline(nodeKey).previous)
    }

    @Test
    fun seenPrefixesSpanAllHistoryNotJustPrevious() = runTest {
        var clock = base
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        val a = neighborPrefix(0xA0)
        val b = neighborPrefix(0xB0)
        service.recordSnapshot(
            nodeKey,
            status = metrics(3700, 3600),
            neighbors = listOf(NeighborSnapshotEntry(a, snr = 6.0, secondsAgo = 30)),
        )
        clock += pastWindow
        service.recordSnapshot(
            nodeKey,
            status = metrics(3800, 5400),
            neighbors = listOf(NeighborSnapshotEntry(b, snr = 7.0, secondsAgo = 30)),
        )
        clock += pastWindow

        val baseline = service.neighborBaseline(nodeKey)
        // Previous is the most recent neighbour-bearing row (B); A has gone silent there.
        assertEquals(b.toList(), baseline.previous?.neighborSnapshots?.first()?.publicKeyPrefix?.toList())
        // The seen set still knows A via older history.
        assertTrue(baseline.seenPrefixes.contains(a.toList()))
        assertTrue(baseline.seenPrefixes.contains(b.toList()))
    }

    // MARK: Status baseline

    @Test
    fun previousStatusSkipsNewerNeighborOnlyRow() = runTest {
        var clock = base
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { clock }))
        service.recordSnapshot(nodeKey, status = metrics(3700, 3600))
        clock += pastWindow
        service.recordSnapshot(
            nodeKey,
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0x01), snr = 9.0, secondsAgo = 5)),
        )

        val previous = service.previousStatusSnapshot(nodeKey, before = clock + 1.0)
        assertEquals(3600L, previous?.uptimeSeconds)
    }

    @Test
    fun previousStatusIncludesTheInWindowStatusRow() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        service.recordSnapshot(nodeKey, status = metrics(3850, 3600))
        val previous = service.previousStatusSnapshot(nodeKey, before = base + 1.0)
        assertEquals(3600L, previous?.uptimeSeconds)
    }

    @Test
    fun previousStatusIsNullWhenOnlyNeighborData() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        service.recordSnapshot(
            nodeKey,
            neighbors = listOf(NeighborSnapshotEntry(neighborPrefix(0x01), snr = 9.0, secondsAgo = 5)),
        )
        assertNull(service.previousStatusSnapshot(nodeKey, before = base + 1.0))
    }

    @Test
    fun concurrentInWindowCapturesCollapseToOneRow() = runTest {
        val service = NodeSnapshotService(InMemoryNodeSnapshotStore(now = { base }))
        val status = async { service.recordSnapshot(nodeKey, status = metrics(3850, 3600)) }
        val telemetry = async {
            service.recordSnapshot(nodeKey, telemetry = listOf(TelemetrySnapshotEntry(0, "temperature", 19.0)))
        }
        assertEquals(status.await(), telemetry.await())
        assertEquals(1, service.fetchSnapshots(nodeKey).size)
    }

    @Test
    fun metricsFromStatusMapsPacketCountersAndClampsDuplicates() {
        val metrics = NodeStatusMetrics.fromStatus(testStatusResponse())
        assertEquals(100L, metrics.sentDirect)
        assertEquals(200L, metrics.sentFlood)
        assertEquals(300L, metrics.receivedDirect)
        assertEquals(400L, metrics.receivedFlood)
        assertEquals(11L, metrics.directDuplicates)
        assertEquals(22L, metrics.floodDuplicates)
        assertEquals(3850, metrics.batteryMillivolts)
        assertEquals(-87, metrics.lastRSSI)
    }
}

package one.meshcore.shared.remotenode

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * In-memory [NodeSnapshotStore] behind a [Mutex], and the test backing for [plannedSnapshot]. Because
 * [recordSnapshot] runs the fetch-latest lookup and the write under one lock with no suspension between
 * them, concurrent captures serialize and never duplicate an in-window row — the same atomicity the Swift
 * `@ModelActor` `recordNodeStatusSnapshot` relies on. [newId] and [now] are injectable so tests can pin
 * ids and timestamps.
 */
@OptIn(ExperimentalTime::class)
class InMemoryNodeSnapshotStore(
    private val newId: () -> Uuid = Uuid::random,
    private val now: () -> Double = { Clock.System.now().epochSeconds.toDouble() },
) : NodeSnapshotStore {
    private val mutex = Mutex()
    private val rows = mutableMapOf<Uuid, NodeStatusSnapshotDto>()

    override suspend fun recordSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics?,
        telemetry: List<TelemetrySnapshotEntry>?,
        neighbors: List<NeighborSnapshotEntry>?,
        location: NodeLocationFix?,
    ): Uuid = mutex.withLock {
        val latest = rows.values
            .filter { it.nodePublicKey.contentEquals(nodePublicKey) }
            .maxByOrNull { it.timestamp }
        val planned = plannedSnapshot(
            latest = latest,
            nowSeconds = now,
            newId = newId,
            capture = NodeSnapshotCapture(nodePublicKey, status, telemetry, neighbors, location),
        )
        rows[planned.row.id] = planned.row
        planned.id
    }

    override suspend fun fetchSnapshots(nodePublicKey: ByteArray, since: Double?): List<NodeStatusSnapshotDto> =
        mutex.withLock { ascendingSnapshots(nodePublicKey).filter { since == null || it.timestamp >= since } }

    override suspend fun fetchLatestSnapshot(nodePublicKey: ByteArray): NodeStatusSnapshotDto? =
        mutex.withLock { ascendingSnapshots(nodePublicKey).lastOrNull() }

    override suspend fun updateSnapshotNeighbors(id: Uuid, neighbors: List<NeighborSnapshotEntry>) = mutex.withLock {
        rows[id]?.let { rows[id] = it.copy(neighborSnapshots = neighbors) }
        Unit
    }

    override suspend fun updateSnapshotTelemetry(id: Uuid, telemetry: List<TelemetrySnapshotEntry>) = mutex.withLock {
        rows[id]?.let { rows[id] = it.copy(telemetryEntries = telemetry) }
        Unit
    }

    override suspend fun deleteSnapshotsOlderThan(cutoff: Double) = mutex.withLock {
        rows.values.filter { it.timestamp < cutoff }.forEach { rows.remove(it.id) }
    }

    override suspend fun neighborBaseline(nodePublicKey: ByteArray): NeighborBaseline = mutex.withLock {
        neighborBaselineFrom(ascendingSnapshots(nodePublicKey), now())
    }

    override suspend fun previousStatusSnapshot(nodePublicKey: ByteArray, before: Double): NodeStatusSnapshotDto? =
        mutex.withLock { previousStatusBefore(ascendingSnapshots(nodePublicKey), before) }

    private fun ascendingSnapshots(nodePublicKey: ByteArray): List<NodeStatusSnapshotDto> =
        rows.values.filter { it.nodePublicKey.contentEquals(nodePublicKey) }.sortedBy { it.timestamp }
}

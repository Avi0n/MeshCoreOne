package one.meshcore.shared.persistence

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NeighborBaseline
import one.meshcore.shared.remotenode.NodeLocationFix
import one.meshcore.shared.remotenode.NodeSnapshotCapture
import one.meshcore.shared.remotenode.NodeSnapshotStore
import one.meshcore.shared.remotenode.NodeStatusMetrics
import one.meshcore.shared.remotenode.neighborBaselineFrom
import one.meshcore.shared.remotenode.plannedSnapshot
import one.meshcore.shared.remotenode.previousStatusBefore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Room-backed [NodeSnapshotStore]. The enrich-vs-insert decision is [plannedSnapshot], with a
 * mutex so concurrent captures serialize across the fetch/upsert window.
 */
@OptIn(ExperimentalTime::class)
class RoomNodeSnapshotStore(
    private val dao: NodeStatusSnapshotDao,
    private val newId: () -> Uuid = Uuid::random,
    private val now: () -> Double = { Clock.System.now().epochSeconds.toDouble() },
) : NodeSnapshotStore {
    private val mutex = Mutex()

    override suspend fun recordSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics?,
        telemetry: List<TelemetrySnapshotEntry>?,
        neighbors: List<NeighborSnapshotEntry>?,
        location: NodeLocationFix?,
    ): Uuid = mutex.withLock {
        val latest = dao.listByNode(nodePublicKey).maxByOrNull { it.timestamp }?.toDto()
        val planned = plannedSnapshot(
            latest = latest,
            nowSeconds = now,
            newId = newId,
            capture = NodeSnapshotCapture(nodePublicKey, status, telemetry, neighbors, location),
        )
        dao.upsert(planned.row.toEntity())
        planned.id
    }

    override suspend fun fetchSnapshots(nodePublicKey: ByteArray, since: Double?): List<NodeStatusSnapshotDto> =
        mutex.withLock {
            dao.listByNode(nodePublicKey)
                .map { it.toDto() }
                .filter { since == null || it.timestamp >= since }
        }

    override suspend fun fetchLatestSnapshot(nodePublicKey: ByteArray): NodeStatusSnapshotDto? =
        mutex.withLock { dao.listByNode(nodePublicKey).lastOrNull()?.toDto() }

    override suspend fun updateSnapshotNeighbors(id: Uuid, neighbors: List<NeighborSnapshotEntry>) = mutex.withLock {
        dao.getById(id)?.toDto()?.let { dao.upsert(it.copy(neighborSnapshots = neighbors).toEntity()) }
        Unit
    }

    override suspend fun updateSnapshotTelemetry(id: Uuid, telemetry: List<TelemetrySnapshotEntry>) = mutex.withLock {
        dao.getById(id)?.toDto()?.let { dao.upsert(it.copy(telemetryEntries = telemetry).toEntity()) }
        Unit
    }

    override suspend fun deleteSnapshotsOlderThan(cutoff: Double) = mutex.withLock {
        dao.deleteOlderThan(cutoff)
    }

    override suspend fun neighborBaseline(nodePublicKey: ByteArray): NeighborBaseline = mutex.withLock {
        neighborBaselineFrom(dao.listByNode(nodePublicKey).map { it.toDto() }, now())
    }

    override suspend fun previousStatusSnapshot(nodePublicKey: ByteArray, before: Double): NodeStatusSnapshotDto? =
        mutex.withLock {
            previousStatusBefore(dao.listByNode(nodePublicKey).map { it.toDto() }, before)
        }
}

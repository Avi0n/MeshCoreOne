package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid

/**
 * Manages node status snapshots with throttled capture, mirroring the Swift `NodeSnapshotService`. A thin
 * layer over [NodeSnapshotStore]: it centralizes the capture and baseline surface the status view models
 * drive, keeping the enrich-vs-insert throttle and delta derivations in the store.
 */
class NodeSnapshotService(private val store: NodeSnapshotStore) {
    /**
     * Captures a status, telemetry, and/or neighbour reading, enriching the latest in-window snapshot or
     * inserting a new one. Returns the affected snapshot's id so callers can target later enrichment.
     */
    suspend fun recordSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics? = null,
        telemetry: List<TelemetrySnapshotEntry>? = null,
        neighbors: List<NeighborSnapshotEntry>? = null,
        location: NodeLocationFix? = null,
    ): Uuid = store.recordSnapshot(nodePublicKey, status, telemetry, neighbors, location)

    /**
     * The neighbour baseline: the previous neighbour-bearing snapshot (for the SNR delta) plus every
     * neighbour prefix seen across history (for the "New" badge). The current in-window capture is excluded.
     */
    suspend fun neighborBaseline(nodePublicKey: ByteArray): NeighborBaseline = store.neighborBaseline(nodePublicKey)

    /** The most recent status-bearing snapshot before [before] epoch seconds, for the status delta. */
    suspend fun previousStatusSnapshot(nodePublicKey: ByteArray, before: Double): NodeStatusSnapshotDto? =
        store.previousStatusSnapshot(nodePublicKey, before)

    /** All snapshots for a node, optionally filtered to at-or-after [since] epoch seconds, ascending. */
    suspend fun fetchSnapshots(nodePublicKey: ByteArray, since: Double? = null): List<NodeStatusSnapshotDto> =
        store.fetchSnapshots(nodePublicKey, since)

    /** Deletes snapshots older than [cutoff] epoch seconds. */
    suspend fun pruneOldSnapshots(cutoff: Double) = store.deleteSnapshotsOlderThan(cutoff)
}

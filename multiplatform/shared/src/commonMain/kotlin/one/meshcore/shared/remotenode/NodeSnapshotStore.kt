package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid

/**
 * The neighbour baseline for a node: the previous neighbour-bearing snapshot (for the SNR delta) plus
 * every neighbour prefix seen across history (for the "New" badge). A prefix is a [List] of bytes so the
 * set has value semantics (a raw `ByteArray` compares by identity). Mirrors the Swift
 * `(previous: NodeStatusSnapshotDTO?, seenPrefixes: Set<Data>)`.
 */
data class NeighborBaseline(val previous: NodeStatusSnapshotDto?, val seenPrefixes: Set<List<Byte>>)

/**
 * The snapshot persistence seam [NodeSnapshotService] depends on. The interface keeps the enrich-vs-insert
 * throttle and delta baselines independently testable while the Room adapter provides production storage.
 * Reproduces the subset of the Swift `NodeSnapshotPersisting` the service calls.
 */
interface NodeSnapshotStore {
    /**
     * Atomically captures a status, telemetry, and/or neighbour snapshot, enriching the latest in-window
     * snapshot or inserting a new one. Within [NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS] of the latest
     * row, status fields are applied only while that row is still telemetry-only (no uptime), preserving
     * the one-status-point-per-window throttle; telemetry and neighbour arrays are applied whenever
     * supplied. A location fix is first-wins: written only when the row has none yet, so a later no-fix
     * can't erase a good one. Outside the window a new snapshot is inserted. Returns the affected row's id.
     */
    suspend fun recordSnapshot(
        nodePublicKey: ByteArray,
        status: NodeStatusMetrics?,
        telemetry: List<TelemetrySnapshotEntry>?,
        neighbors: List<NeighborSnapshotEntry>?,
        location: NodeLocationFix? = null,
    ): Uuid

    /** All snapshots for a node at or after [since] epoch seconds (null for all), ascending by timestamp. */
    suspend fun fetchSnapshots(nodePublicKey: ByteArray, since: Double?): List<NodeStatusSnapshotDto>

    /** The most recent snapshot for a node, or null. */
    suspend fun fetchLatestSnapshot(nodePublicKey: ByteArray): NodeStatusSnapshotDto?

    /** Replaces the neighbour array on the snapshot with local id [id]; a no-op if absent. */
    suspend fun updateSnapshotNeighbors(id: Uuid, neighbors: List<NeighborSnapshotEntry>)

    /** Replaces the telemetry array on the snapshot with local id [id]; a no-op if absent. */
    suspend fun updateSnapshotTelemetry(id: Uuid, telemetry: List<TelemetrySnapshotEntry>)

    /** Deletes snapshots older than [cutoff] epoch seconds. */
    suspend fun deleteSnapshotsOlderThan(cutoff: Double)

    /**
     * The neighbour baseline (previous neighbour-bearing snapshot + all seen prefixes). Both derive from
     * one in-window cutoff that excludes the reading being viewed, so it is never diffed against itself.
     * Status- or telemetry-only rows are skipped.
     */
    suspend fun neighborBaseline(nodePublicKey: ByteArray): NeighborBaseline

    /**
     * The most recent snapshot carrying status fields (uptime present) before [before] epoch seconds, for
     * the status delta. Skips neighbour- or telemetry-only rows so a sparse capture cannot blank the delta;
     * the in-window row is kept, since status is throttled and never overwrites itself.
     */
    suspend fun previousStatusSnapshot(nodePublicKey: ByteArray, before: Double): NodeStatusSnapshotDto?
}

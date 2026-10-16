package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.model.Uuid

/** The row a capture should write, and the id [NodeSnapshotStore.recordSnapshot] returns. */
internal data class PlannedSnapshot(val id: Uuid, val row: NodeStatusSnapshotDto)

/** The measurements from one node capture, merged as a unit. */
internal data class NodeSnapshotCapture(
    val nodePublicKey: ByteArray,
    val status: NodeStatusMetrics?,
    val telemetry: List<TelemetrySnapshotEntry>?,
    val neighbors: List<NeighborSnapshotEntry>?,
    val location: NodeLocationFix?,
)

/**
 * Enrich-vs-insert decision shared by the in-memory store and the Room adapter.
 * The window check and a new row's timestamp each read [nowSeconds], so an insert can land
 * a moment after the check that chose it. [newId] runs only for an insert.
 */
internal fun plannedSnapshot(
    latest: NodeStatusSnapshotDto?,
    nowSeconds: () -> Double,
    newId: () -> Uuid,
    capture: NodeSnapshotCapture,
): PlannedSnapshot {
    val nodePublicKey = capture.nodePublicKey
    val status = capture.status
    val telemetry = capture.telemetry
    val neighbors = capture.neighbors
    val location = capture.location
    if (latest != null && nowSeconds() - latest.timestamp < NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS) {
        var updated = latest
        if (status != null && latest.uptimeSeconds == null) updated = updated.applying(status)
        if (telemetry != null) updated = updated.copy(telemetryEntries = telemetry)
        if (neighbors != null) updated = updated.copy(neighborSnapshots = neighbors)
        if (location != null && latest.latitude == null) updated = updated.applying(location)
        return PlannedSnapshot(id = latest.id, row = updated)
    }
    val id = newId()
    var snapshot = NodeStatusSnapshotDto(
        id = id,
        timestamp = nowSeconds(),
        nodePublicKey = nodePublicKey,
        telemetryEntries = telemetry,
    )
    if (status != null) snapshot = snapshot.applying(status)
    if (neighbors != null) snapshot = snapshot.copy(neighborSnapshots = neighbors)
    if (location != null) snapshot = snapshot.applying(location)
    return PlannedSnapshot(id = id, row = snapshot)
}

/**
 * Neighbour baseline from one node's history in the order the store lists it.
 * The in-window cutoff excludes the reading being viewed so it is never diffed against itself.
 */
internal fun neighborBaselineFrom(history: List<NodeStatusSnapshotDto>, nowSeconds: Double): NeighborBaseline {
    val latest = history.lastOrNull()
    val cutoff = if (latest != null && nowSeconds - latest.timestamp < NodeSnapshotPolicy.MINIMUM_INTERVAL_SECONDS) {
        latest.timestamp
    } else {
        nowSeconds
    }
    val older = history.filter { it.timestamp < cutoff && it.neighborSnapshots != null }
    val seen = older
        .flatMap { it.neighborSnapshots ?: emptyList() }
        .map { it.publicKeyPrefix.toList() }
        .toSet()
    return NeighborBaseline(previous = older.lastOrNull(), seenPrefixes = seen)
}

/** Most recent status-bearing snapshot before [before]. Skips neighbour- or telemetry-only rows. */
internal fun previousStatusBefore(history: List<NodeStatusSnapshotDto>, before: Double): NodeStatusSnapshotDto? =
    history.lastOrNull { it.timestamp < before && it.uptimeSeconds != null }

/** Applies captured status metrics onto a snapshot, leaving neighbour and telemetry data untouched. */
private fun NodeStatusSnapshotDto.applying(metrics: NodeStatusMetrics): NodeStatusSnapshotDto = copy(
    batteryMillivolts = metrics.batteryMillivolts,
    lastSNR = metrics.lastSNR,
    lastRSSI = metrics.lastRSSI,
    noiseFloor = metrics.noiseFloor,
    uptimeSeconds = metrics.uptimeSeconds,
    rxAirtimeSeconds = metrics.rxAirtimeSeconds,
    packetsSent = metrics.packetsSent,
    packetsReceived = metrics.packetsReceived,
    receiveErrors = metrics.receiveErrors,
    sentDirect = metrics.sentDirect,
    sentFlood = metrics.sentFlood,
    receivedDirect = metrics.receivedDirect,
    receivedFlood = metrics.receivedFlood,
    directDuplicates = metrics.directDuplicates,
    floodDuplicates = metrics.floodDuplicates,
    postedCount = metrics.postedCount,
    postPushCount = metrics.postPushCount,
)

/** Stamps a first-wins GPS fix onto a snapshot; callers gate the "first" check. */
private fun NodeStatusSnapshotDto.applying(location: NodeLocationFix): NodeStatusSnapshotDto = copy(
    latitude = location.latitude,
    longitude = location.longitude,
    altitude = location.altitude,
)

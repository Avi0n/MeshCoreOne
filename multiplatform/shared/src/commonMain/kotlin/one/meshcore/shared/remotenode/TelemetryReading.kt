package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.TelemetrySnapshotEntry

/**
 * Numeric entries plus a plottable fix from one telemetry reply. Null when the reply has neither,
 * so a lock-less node with no sensors does not open a snapshot.
 */
internal data class TelemetryReading(val entries: List<TelemetrySnapshotEntry>, val location: NodeLocationFix?)

internal fun List<LPPDataPoint>.telemetryReadingOrNull(): TelemetryReading? {
    val entries = telemetrySnapshotEntries()
    val location = NodeLocationFix.primaryFix(this)
    if (entries.isEmpty() && location == null) return null
    return TelemetryReading(entries, location)
}

internal suspend fun NodeSnapshotService.recordTelemetryReading(nodePublicKey: ByteArray, reading: TelemetryReading) {
    runNodeSnapshot(onFailure = {}) {
        recordSnapshot(
            nodePublicKey = nodePublicKey,
            telemetry = reading.entries.ifEmpty { null },
            location = reading.location,
        )
        Unit
    }
}

internal suspend fun recordTelemetryPoints(
    points: List<LPPDataPoint>,
    nodePublicKey: ByteArray,
    snapshots: NodeSnapshotService?,
) {
    val reading = points.telemetryReadingOrNull() ?: return
    snapshots?.recordTelemetryReading(nodePublicKey, reading)
}

package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.model.toUppercaseHex

/**
 * Data prep for the single-neighbour SNR history chart, mirroring the Swift `NeighborSNRChartView`'s
 * `task` block. For each snapshot carrying a neighbour reading whose 6-byte prefix matches [neighborPrefix],
 * emits one point at the snapshot's timestamp with the recorded SNR. The caller applies the time-range
 * window (via [HistoryTimeRange.filter]) and renders the series with unit "dB". This is the chart *data*;
 * the neighbour SNR *map* is [NeighborSnrMapBuilder].
 */
fun neighborSnrSeries(snapshots: List<NodeStatusSnapshotDto>, neighborPrefix: ByteArray): List<TelemetryChartPoint> =
    snapshots.mapNotNull { snapshot ->
        val match = snapshot.neighborSnapshots
            ?.firstOrNull { it.publicKeyPrefix.contentEquals(neighborPrefix) }
            ?: return@mapNotNull null
        TelemetryChartPoint(id = snapshot.id, timestampSeconds = snapshot.timestamp, value = match.snr)
    }

/**
 * One neighbour's SNR history for the overview Neighbors section, mirroring Swift
 * `TelemetryHistoryOverviewView.buildNeighborCharts`. [name] is a resolved contact name or hex fallback.
 */
data class NeighborSnrChart(val prefix: ByteArray, val name: String, val points: List<TelemetryChartPoint>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeighborSnrChart) return false
        return name == other.name && points == other.points && prefix.contentEquals(other.prefix)
    }

    override fun hashCode(): Int = 31 * (31 * prefix.contentHashCode() + name.hashCode()) + points.hashCode()
}

/**
 * Aggregates every distinct neighbour prefix across [snapshots] into SNR series, sorted by name.
 * [nameResolver] may map a prefix to a contact display name; null falls back to uppercase hex.
 */
fun neighborSnrCharts(
    snapshots: List<NodeStatusSnapshotDto>,
    nameResolver: (ByteArray) -> String? = { null },
): List<NeighborSnrChart> {
    val byPrefix = linkedMapOf<String, Pair<ByteArray, MutableList<TelemetryChartPoint>>>()
    for (snapshot in snapshots) {
        for (neighbor in snapshot.neighborSnapshots.orEmpty()) {
            val key = neighbor.publicKeyPrefix.toUppercaseHex()
            val bucket = byPrefix.getOrPut(key) {
                neighbor.publicKeyPrefix to mutableListOf()
            }
            bucket.second.add(
                TelemetryChartPoint(
                    id = snapshot.id,
                    timestampSeconds = snapshot.timestamp,
                    value = neighbor.snr,
                ),
            )
        }
    }
    return byPrefix.values
        .map { (prefix, points) ->
            NeighborSnrChart(
                prefix = prefix,
                name = nameResolver(prefix) ?: prefix.toUppercaseHex(),
                points = points,
            )
        }
        .sortedBy { it.name }
}

package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.meshcore.StatusResponse
import kotlin.math.abs

/**
 * Trend deltas between a live status reading and the previous status-bearing snapshot.
 * Null fields mean the previous snapshot lacked that metric (or there is no baseline).
 */
data class StatusMetricDeltas(
    val batteryVolts: Double? = null,
    val snr: Double? = null,
    val rssi: Double? = null,
    val noiseFloor: Double? = null,
)

/**
 * Pure delta / neighbor-history helpers for the status screen, mirroring the computed properties on
 * the iOS `NodeStatusViewModel` (battery / SNR / RSSI / noise floor) and the neighbor New/disappeared
 * rules in `RepeaterStatusContent`.
 */
object NodeStatusDeltas {
    private const val MILLIVOLTS_PER_VOLT = 1000.0
    private const val NEAR_ZERO = 0.01
    private const val SNR_DELTA_MIN = 0.1

    /**
     * Voltage delta in volts (not mV) so the UI can append the same " V" unit as the live voltage row.
     * Matches iOS `batteryDeltaMV.map { Double($0) / 1000 }`.
     */
    fun fromStatus(current: StatusResponse, previous: NodeStatusSnapshotDto?): StatusMetricDeltas {
        if (previous == null) return StatusMetricDeltas()
        return StatusMetricDeltas(
            batteryVolts = previous.batteryMillivolts?.let {
                (current.battery - it).toDouble() / MILLIVOLTS_PER_VOLT
            },
            snr = previous.lastSNR?.let { current.lastSNR - it },
            rssi = previous.lastRSSI?.let { (current.lastRSSI - it).toDouble() },
            noiseFloor = previous.noiseFloor?.let { (current.noiseFloor - it).toDouble() },
        )
    }

    /** Whether [delta] is large enough to render a trend arrow (iOS uses abs < 0.01 for neutral). */
    fun isVisible(delta: Double): Boolean = abs(delta) >= NEAR_ZERO

    /**
     * SNR delta vs the previous neighbor-bearing snapshot for the same prefix.
     * Returns null when the baseline lacks that prefix or the change is under 0.1 dB (iOS gate).
     */
    fun neighborSnrDelta(current: Neighbour, previousEntries: List<NeighborSnapshotEntry>?): Double? {
        val previous = previousEntries?.firstOrNull { it.publicKeyPrefix.contentEquals(current.publicKeyPrefix) }
            ?: return null
        val delta = current.snr - previous.snr
        return if (abs(delta) >= SNR_DELTA_MIN) delta else null
    }

    /**
     * True when history has a prior neighbor-bearing snapshot and this prefix was never seen before
     * (iOS "New" badge). Requires a non-null previous snapshot so the first-ever capture does not
     * mark every neighbor as new.
     */
    fun isNewNeighbor(
        prefix: ByteArray,
        previousNeighborSnapshot: NodeStatusSnapshotDto?,
        seenPrefixes: Set<List<Byte>>,
    ): Boolean {
        if (previousNeighborSnapshot?.neighborSnapshots == null) return false
        return prefix.toList() !in seenPrefixes
    }

    /**
     * Neighbors present on the previous neighbor-bearing snapshot but absent from [current],
     * for the greyed "Not seen" rows.
     */
    fun disappearedNeighbors(
        current: List<Neighbour>,
        previousEntries: List<NeighborSnapshotEntry>?,
    ): List<NeighborSnapshotEntry> {
        if (previousEntries.isNullOrEmpty()) return emptyList()
        return previousEntries.filter { old ->
            current.none { it.publicKeyPrefix.contentEquals(old.publicKeyPrefix) }
        }
    }
}

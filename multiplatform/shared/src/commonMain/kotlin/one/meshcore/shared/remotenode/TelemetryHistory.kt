package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.model.Uuid

/**
 * Time window for filtering history charts, mirroring the Swift `HistoryTimeRange` (default [MONTH]).
 * The Swift start date subtracts calendar units (`byAdding: .month`); without a date library here, the
 * window is a fixed span of days ([MONTH] = 30, [THREE_MONTHS] = 90), a small divergence at month
 * boundaries that does not change which charts draw. [ALL] returns null (no lower bound). Timestamps are
 * epoch seconds; the caller supplies `now` so the window is deterministic in tests.
 */
enum class HistoryTimeRange(private val spanDays: Int?) {
    WEEK(7),
    MONTH(30),
    THREE_MONTHS(90),
    ALL(null),
    ;

    /** The inclusive lower bound in epoch seconds, or null for [ALL]. */
    fun startEpochSeconds(nowEpochSeconds: Double): Double? = spanDays?.let { nowEpochSeconds - it * SECONDS_PER_DAY }

    /** [snapshots] at or after the window's start, keeping order. */
    fun filter(snapshots: List<NodeStatusSnapshotDto>, nowEpochSeconds: Double): List<NodeStatusSnapshotDto> {
        val start = startEpochSeconds(nowEpochSeconds) ?: return snapshots
        return snapshots.filter { it.timestamp >= start }
    }

    companion object {
        val DEFAULT = MONTH
        private const val SECONDS_PER_DAY = 86_400.0
    }
}

/**
 * One point on a telemetry chart, mirroring the Swift `MetricChartView.DataPoint`. [id] is the source
 * snapshot's id (so a scrubbed sample maps back to its row); [timestampSeconds] is the X value.
 */
data class TelemetryChartPoint(val id: Uuid, val timestampSeconds: Double, val value: Double)

/**
 * A per-(channel, sensor-type) chart series, mirroring the Swift `TelemetryChartGroup`. [title] is the
 * English sensor name; the app target substitutes the localized name at render (which also becomes the
 * sort tiebreak there). [sensorType] is null for an unrecognized persisted type string.
 */
data class TelemetryChartGroup(
    val key: String,
    val title: String,
    val sensorType: LPPSensorType?,
    val points: List<TelemetryChartPoint>,
) {
    val isMcuTemperature: Boolean
        get() = key.endsWith("-${LPPSensorType.TEMPERATURE.wireName}-mcu")
}

/**
 * A channel's telemetry charts, mirroring the Swift `ChannelGroup`. Built from persisted snapshot entries
 * (not live LPP points), so it feeds both the live drill-down and the offline overview.
 */
data class ChannelGroup(val channel: Int, val charts: List<TelemetryChartGroup>) {
    companion object {
        /**
         * Groups the telemetry entries across [snapshots] by channel then sensor type, converting each
         * value to [system]'s unit. Channels sort ascending; charts within a channel sort by
         * [LPPSensorType.chartSortPriority] then title. Mirrors `ChannelGroup.groups(from:)`.
         */
        fun groups(
            snapshots: List<NodeStatusSnapshotDto>,
            system: MeasurementSystem = MeasurementSystem.METRIC,
        ): List<ChannelGroup> {
            val byChannelType = linkedMapOf<Int, LinkedHashMap<String, MutableList<TelemetryChartPoint>>>()

            for (snapshot in snapshots) {
                val temperatureCountByChannel = mutableMapOf<Int, Int>()
                for (entry in snapshot.telemetryEntries.orEmpty()) {
                    val sensorType = LPPSensorType.fromName(entry.type)
                    val value = sensorType?.convertedValue(entry.value, system) ?: entry.value
                    val chartType = chartType(entry.channel, entry.type, sensorType, temperatureCountByChannel)
                    byChannelType
                        .getOrPut(entry.channel) { linkedMapOf() }
                        .getOrPut(chartType) { mutableListOf() }
                        .add(TelemetryChartPoint(snapshot.id, snapshot.timestamp, value))
                }
            }

            return byChannelType.keys.sorted().map { channel ->
                val charts = byChannelType.getValue(channel).entries
                    .map { (type, points) ->
                        val sensorType = LPPSensorType.fromName(type.removeSuffix("-mcu"))
                        TelemetryChartGroup(
                            key = "$channel-$type",
                            title = sensorType?.wireName ?: type,
                            sensorType = sensorType,
                            points = points,
                        )
                    }
                    .sortedWith(compareBy({ it.sensorType?.chartSortPriority ?: 1 }, { it.title }))
                ChannelGroup(channel = channel, charts = charts)
            }
        }

        private fun chartType(
            channel: Int,
            type: String,
            sensorType: LPPSensorType?,
            temperatureCountByChannel: MutableMap<Int, Int>,
        ): String {
            if (sensorType != LPPSensorType.TEMPERATURE) return type
            val seen = temperatureCountByChannel.getOrDefault(channel, 0)
            temperatureCountByChannel[channel] = seen + 1
            return if (seen > 0) "$type-mcu" else type
        }
    }
}

/**
 * Presence checks for the offline telemetry-history overview, mirroring the Swift
 * `TelemetryHistoryOverviewViewModel`'s `has*Data` computed properties. Each gates whether a section
 * renders for a set of (already time-range-filtered) snapshots.
 */
object TelemetryOverview {
    /** Any snapshot carries at least one neighbour reading. */
    fun hasNeighborData(snapshots: List<NodeStatusSnapshotDto>): Boolean =
        snapshots.any { !it.neighborSnapshots.isNullOrEmpty() }

    /**
     * Charts when Saved History includes Neighbors and snapshots carry SNR rows. Chat contacts pass
     * [showNeighbors] false so a companion never shows repeater SNR (iOS `showNeighbors: false`).
     */
    fun showsNeighborCharts(showNeighbors: Boolean, hasNeighborData: Boolean): Boolean =
        showNeighbors && hasNeighborData

    /** Not-captured note when Neighbors is in scope but no snapshot has a neighbour row. */
    fun showsNeighborsNotCaptured(showNeighbors: Boolean, hasNeighborData: Boolean): Boolean =
        showNeighbors && !hasNeighborData

    /** Any snapshot carries at least one telemetry entry. */
    fun hasTelemetryData(snapshots: List<NodeStatusSnapshotDto>): Boolean =
        snapshots.any { !it.telemetryEntries.isNullOrEmpty() }

    /** Any snapshot carries at least one radio or room-server metric. */
    fun hasRadioData(snapshots: List<NodeStatusSnapshotDto>): Boolean = snapshots.any { snapshot ->
        listOfNotNull(
            snapshot.batteryMillivolts, snapshot.lastSNR, snapshot.lastRSSI, snapshot.noiseFloor,
            snapshot.packetsSent, snapshot.packetsReceived, snapshot.receiveErrors,
            snapshot.sentDirect, snapshot.sentFlood, snapshot.receivedDirect, snapshot.receivedFlood,
            snapshot.directDuplicates, snapshot.floodDuplicates, snapshot.postedCount, snapshot.postPushCount,
        ).isNotEmpty()
    }
}

package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.battery.OCVPreset

/**
 * One radio-metric chart series extracted from node status snapshots, mirroring the single-series
 * rows in Swift `RadioMetricCharts` (battery, SNR, RSSI, noise floor, posts). Dual Direct/Flood
 * overlays are [RadioPacketOverlay]. [yAxisDomain] anchors the battery series to the OCV curve range
 * (see [RadioMetricCharts.voltageChartDomain]); other series autoscale to their samples (null).
 */
data class RadioMetricSeries(
    val key: String,
    val points: List<TelemetryChartPoint>,
    val unit: String,
    val yAxisDomain: ClosedRange<Double>? = null,
)

/**
 * Direct + flood packet overlay on a shared Y scale, mirroring Swift packet-count overlays.
 * Either series may be empty; both empty means the chart is omitted by the builder. [yAxisDomain]
 * is the shared 0-anchored range from [RadioMetricCharts.sharedDomain] so an all-flood and an
 * all-direct chart read on the same scale.
 */
data class RadioPacketOverlay(
    val key: String,
    val direct: List<TelemetryChartPoint>,
    val flood: List<TelemetryChartPoint>,
    val yAxisDomain: ClosedRange<Double>? = null,
) {
    val hasData: Boolean get() = direct.isNotEmpty() || flood.isNotEmpty()
}

/**
 * Pure radio-metric chart data for the telemetry history overview. Charts with no samples are omitted.
 * Titles and legend labels stay in the UI layer (localized strings).
 */
object RadioMetricCharts {
    const val KEY_BATTERY = "battery"
    const val KEY_SNR = "snr"
    const val KEY_RSSI = "rssi"
    const val KEY_NOISE_FLOOR = "noiseFloor"
    const val KEY_POSTS_RECEIVED = "postsReceived"
    const val KEY_POSTS_PUSHED = "postsPushed"
    const val KEY_PACKETS_SENT = "packetsSent"
    const val KEY_PACKETS_RECEIVED = "packetsReceived"
    const val KEY_DUPLICATES = "duplicates"
    const val KEY_RECEIVE_ERRORS = "receiveErrors"

    const val UNIT_VOLTS = "V"
    const val UNIT_DB = "dB"
    const val UNIT_DBM = "dBm"
    const val UNIT_COUNT = ""

    private const val MILLIVOLTS_PER_VOLT = 1000.0

    /**
     * Buffer added above/below the OCV curve's min/max in [voltageChartDomain], in millivolts.
     * Mirrors iOS `voltageChartDomain(bufferMV:)`'s default.
     */
    const val VOLTAGE_DOMAIN_BUFFER_MV = 500

    /**
     * Headroom multiplier applied to the maximum sample in [sharedDomain], so the highest point
     * isn't drawn flush against the plot's top edge. Mirrors iOS `sharedDomain`'s `* 1.05`.
     */
    private const val SHARED_DOMAIN_HEADROOM = 1.05

    /** Single-series radio charts present in [snapshots], in display order. */
    fun singleSeries(
        snapshots: List<NodeStatusSnapshotDto>,
        ocvArray: List<Int> = OCVPreset.LI_ION.ocvArray,
    ): List<RadioMetricSeries> = buildList {
        val batteryPoints = snapshots.mapNotNull { s ->
            s.batteryMillivolts?.let {
                TelemetryChartPoint(s.id, s.timestamp, it / MILLIVOLTS_PER_VOLT)
            }
        }
        series(
            KEY_BATTERY,
            UNIT_VOLTS,
            batteryPoints,
            yAxisDomain = voltageChartDomain(ocvArray, batteryPoints),
        )?.let(::add)
        series(
            KEY_SNR,
            UNIT_DB,
            snapshots.mapNotNull { s ->
                s.lastSNR?.let { TelemetryChartPoint(s.id, s.timestamp, it) }
            },
        )?.let(::add)
        series(
            KEY_RSSI,
            UNIT_DBM,
            snapshots.mapNotNull { s ->
                s.lastRSSI?.let { TelemetryChartPoint(s.id, s.timestamp, it.toDouble()) }
            },
        )?.let(::add)
        series(
            KEY_NOISE_FLOOR,
            UNIT_DBM,
            snapshots.mapNotNull { s ->
                s.noiseFloor?.let { TelemetryChartPoint(s.id, s.timestamp, it.toDouble()) }
            },
        )?.let(::add)
        series(
            KEY_RECEIVE_ERRORS,
            UNIT_COUNT,
            longPoints(snapshots) { it.receiveErrors },
        )?.let(::add)
        series(
            KEY_POSTS_RECEIVED,
            UNIT_COUNT,
            snapshots.mapNotNull { s ->
                s.postedCount?.let { TelemetryChartPoint(s.id, s.timestamp, it.toDouble()) }
            },
        )?.let(::add)
        series(
            KEY_POSTS_PUSHED,
            UNIT_COUNT,
            snapshots.mapNotNull { s ->
                s.postPushCount?.let { TelemetryChartPoint(s.id, s.timestamp, it.toDouble()) }
            },
        )?.let(::add)
    }

    /** Direct/Flood packet overlays present in [snapshots], each on its own shared 0-anchored scale. */
    fun packetOverlays(snapshots: List<NodeStatusSnapshotDto>): List<RadioPacketOverlay> = listOf(
        packetOverlay(
            KEY_PACKETS_SENT,
            longPoints(snapshots) { it.sentDirect },
            longPoints(snapshots) { it.sentFlood },
        ),
        packetOverlay(
            KEY_PACKETS_RECEIVED,
            longPoints(snapshots) { it.receivedDirect },
            longPoints(snapshots) { it.receivedFlood },
        ),
        packetOverlay(
            KEY_DUPLICATES,
            longPoints(snapshots) { it.directDuplicates },
            longPoints(snapshots) { it.floodDuplicates },
        ),
    ).filter { it.hasData }

    /**
     * Y-axis for a sensor history chart: voltage series are anchored to the OCV curve, everything
     * else autoscales. Mirrors iOS `TelemetryHistoryOverviewView.chartView`.
     */
    fun sensorYAxisDomain(
        sensorType: LPPSensorType?,
        points: List<TelemetryChartPoint>,
        ocvArray: List<Int>,
    ): ClosedRange<Double>? = if (sensorType == LPPSensorType.VOLTAGE) voltageChartDomain(ocvArray, points) else null

    /**
     * Chart Y-axis domain in volts from millivolt OCV values, with a buffer above and below.
     * Unions the OCV range with the actual battery samples so an outlier reading is never clipped.
     * Anchoring to the curve (rather than autoscaling to the visible samples) keeps small
     * near-full-charge fluctuations from being drawn as if they were dramatic, and keeps a
     * low-battery reading visually distinguishable from the real low-voltage cutoff. Mirrors iOS
     * `[Int].voltageChartDomain(dataPoints:bufferMV:)`.
     */
    fun voltageChartDomain(
        ocvArray: List<Int>,
        dataPoints: List<TelemetryChartPoint> = emptyList(),
        bufferMV: Int = VOLTAGE_DOMAIN_BUFFER_MV,
    ): ClosedRange<Double>? {
        val ocvMin = ocvArray.minOrNull() ?: return null
        val ocvMax = ocvArray.maxOrNull() ?: return null
        val sampleValues = dataPoints.map { it.value }
        val lo = minOf(ocvMin / MILLIVOLTS_PER_VOLT, sampleValues.minOrNull() ?: Double.POSITIVE_INFINITY)
        val hi = maxOf(ocvMax / MILLIVOLTS_PER_VOLT, sampleValues.maxOrNull() ?: Double.NEGATIVE_INFINITY)
        val buffer = bufferMV / MILLIVOLTS_PER_VOLT
        return maxOf(0.0, lo - buffer)..(hi + buffer)
    }

    /**
     * Common 0-anchored Y-axis domain spanning every array's max, with [SHARED_DOMAIN_HEADROOM]
     * headroom, or `null` when there is no positive max (empty or all-zero data). Used for the
     * packet-count overlays so an all-flood and an all-direct chart read on the same scale, and a
     * counter naturally at zero doesn't get an artificially tight range. Mirrors iOS
     * `[MetricChartView.DataPoint].sharedDomain(for:)`.
     */
    fun sharedDomain(arrays: List<List<TelemetryChartPoint>>): ClosedRange<Double>? {
        val maxVal = arrays.asSequence().flatten().maxOfOrNull { it.value }
        if (maxVal == null || maxVal <= 0.0) return null
        return 0.0..(maxVal * SHARED_DOMAIN_HEADROOM)
    }

    private fun packetOverlay(
        key: String,
        direct: List<TelemetryChartPoint>,
        flood: List<TelemetryChartPoint>,
    ): RadioPacketOverlay = RadioPacketOverlay(key, direct, flood, yAxisDomain = sharedDomain(listOf(direct, flood)))

    private fun series(
        key: String,
        unit: String,
        points: List<TelemetryChartPoint>,
        yAxisDomain: ClosedRange<Double>? = null,
    ): RadioMetricSeries? = if (points.isEmpty()) null else RadioMetricSeries(key, points, unit, yAxisDomain)

    private fun longPoints(
        snapshots: List<NodeStatusSnapshotDto>,
        selector: (NodeStatusSnapshotDto) -> Long?,
    ): List<TelemetryChartPoint> = snapshots.mapNotNull { s ->
        selector(s)?.let { TelemetryChartPoint(s.id, s.timestamp, it.toDouble()) }
    }
}

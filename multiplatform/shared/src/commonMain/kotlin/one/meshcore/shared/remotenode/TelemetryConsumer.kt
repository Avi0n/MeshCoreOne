package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.TelemetrySnapshotEntry

/**
 * Consumption of decoded telemetry points, mirroring the shape the Swift `NodeStatusViewModel` derives
 * in `handleTelemetryResponse`. Points arrive already decoded on `TelemetryResponse.points`; MeshCore's
 * Swift decoder parses the LPP frame. These helpers only reshape [LPPDataPoint]s for display and
 * persistence. Channel 0 is dropped
 * throughout: `TELEM_CHANNEL_SELF` is 1 (battery/self), and channel 0 is the firmware's internal frame
 * the UI never shows.
 */

/** Channel index that carries no user-visible reading and is filtered from every consumer view. */
private const val FILTERED_CHANNEL: Int = 0

/** The points the UI renders: every decoded point except channel 0. */
fun List<LPPDataPoint>.displayablePoints(): List<LPPDataPoint> = filter { it.channel != FILTERED_CHANNEL }

/** True when the displayable points span more than one channel (drives grouped-vs-flat rendering). */
fun List<LPPDataPoint>.hasMultipleChannels(): Boolean = displayablePoints().map { it.channel }.toSet().size > 1

/** Displayable points grouped by channel, ascending, mirroring `groupedDataPoints`. */
fun List<LPPDataPoint>.groupedByChannel(): List<Pair<Int, List<LPPDataPoint>>> =
    displayablePoints().groupBy { it.channel }.toList().sortedBy { it.first }

/** A second temperature on one channel in this reading represents the MCU sensor. */
fun isMcuTemperatureAt(index: Int, points: List<LPPDataPoint>): Boolean {
    val point = points.getOrNull(index) ?: return false
    return point.type == LPPSensorType.TEMPERATURE &&
        points.take(index).any {
            it.channel == point.channel && it.type == LPPSensorType.TEMPERATURE
        }
}

/**
 * The snapshot entries to persist for a telemetry reading: one per numeric ([LPPValue.FloatValue] or
 * [LPPValue.Integer]) displayable point, carrying the SI value and the English wire type name (the
 * reverse-lookup key). Non-numeric points (digital, GPS, vector, colour, timestamp) are skipped, matching
 * the Swift `compactMap`.
 */
fun List<LPPDataPoint>.telemetrySnapshotEntries(): List<TelemetrySnapshotEntry> =
    displayablePoints().mapNotNull { point ->
        val numericValue = when (val value = point.value) {
            is LPPValue.FloatValue -> value.value
            is LPPValue.Integer -> value.value.toDouble()
            else -> null
        } ?: return@mapNotNull null
        TelemetrySnapshotEntry(channel = point.channel, type = point.type.wireName, value = numericValue)
    }

package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.chat.ui.toTimestampEpochSecondOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

/** The coarse unit a location report's recency rounds to, mapped to a localized "%d… ago" string in the UI. */
enum class RecencyUnit { SECONDS, MINUTES, HOURS, DAYS }

/** A rounded relative recency: a whole [value] in the coarsest [unit] that still holds it. */
data class Recency(val unit: RecencyUnit, val value: Int)

/**
 * Formats a single location report (one [NodeStatusSnapshotDto]) for the History list: a coarse
 * relative recency, an absolute timestamp, and fixed-precision coordinates and altitude. Pure and
 * side-effect free so rows stay trivially unit-testable. Mirrors the iOS `LocationReportFormat`.
 */
object LocationReportFormat {
    /** Joins the absolute time and coordinates (and altitude) on a report's detail line. */
    const val SEPARATOR = ", "

    /** Four decimals (~11 m) distinguishes adjacent reports without implying false GPS precision. */
    private const val COORDINATE_DECIMALS = 4
    private const val COORDINATE_PRECISION = 10_000L

    private const val SECONDS_PER_MINUTE = 60.0
    private const val SECONDS_PER_HOUR = 3600.0
    private const val SECONDS_PER_DAY = 86_400.0

    /**
     * The rounded recency of a report at [deltaSeconds] before now: the coarsest unit that still holds a
     * whole value. Coarse by design — reports land minutes apart, so the absolute timestamp is what
     * distinguishes adjacent rows. Negative deltas (a clock-skewed future stamp) clamp to zero seconds.
     */
    fun relativeRecency(deltaSeconds: Double): Recency {
        val delta = deltaSeconds.coerceAtLeast(0.0)
        return when {
            delta < SECONDS_PER_MINUTE -> Recency(RecencyUnit.SECONDS, delta.toInt())
            delta < SECONDS_PER_HOUR -> Recency(RecencyUnit.MINUTES, (delta / SECONDS_PER_MINUTE).toInt())
            delta < SECONDS_PER_DAY -> Recency(RecencyUnit.HOURS, (delta / SECONDS_PER_HOUR).toInt())
            else -> Recency(RecencyUnit.DAYS, (delta / SECONDS_PER_DAY).toInt())
        }
    }

    /** "37.7847, -122.4012": fixed-precision coordinates with a locale-independent decimal point. */
    fun coordinates(latitude: Double, longitude: Double): String = "${fixed(latitude)}, ${fixed(longitude)}"

    /** "42 m" / "136 ft": altitude rounded to whole units in the locale's preferred length. */
    fun altitude(
        meters: Double,
        system: MeasurementSystem = MeasurementSystem.METRIC,
        symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
    ): String {
        val converted = LPPSensorType.ALTITUDE.convertedValue(meters, system)
        val unit = LPPSensorType.ALTITUDE.localizedUnitSymbol(system, symbols)
        return "${converted.roundToLong()} $unit"
    }

    /**
     * "Jul 13, 07:41": the report's local month, day, and short clock
     * (iOS `.dateTime.month().day().hour().minute()`).
     */
    internal fun absoluteTime(epochSeconds: Double, formatter: TimestampFormatter): String {
        val epoch = epochSeconds.toTimestampEpochSecondOrNull() ?: return formatter.unavailable
        return formatter.monthDayClock(epoch) ?: formatter.unavailable
    }

    private fun fixed(value: Double): String {
        val scaled = (abs(value) * COORDINATE_PRECISION).roundToLong()
        val integer = scaled / COORDINATE_PRECISION
        val fraction = (scaled % COORDINATE_PRECISION).toString().padStart(COORDINATE_DECIMALS, '0')
        val sign = if (value < 0) "-" else ""
        return "$sign$integer.$fraction"
    }
}

/**
 * The location History list: every snapshot carrying a valid fix, newest first. Snapshots arrive in
 * ascending time order (the fetch and the builder's precondition), so reversing yields newest-first.
 * Undecimated, unlike the 60-pin map cap — the list is the full record. Mirrors the iOS `locationReports`.
 */
fun locationReports(snapshots: List<NodeStatusSnapshotDto>): List<NodeStatusSnapshotDto> =
    snapshots.filter { it.validCoordinate() != null }.reversed()

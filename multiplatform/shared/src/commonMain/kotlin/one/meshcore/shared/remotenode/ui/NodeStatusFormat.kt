package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.meshcore.StatusResponse
import kotlin.math.abs
import kotlin.math.roundToLong

/** Uptime broken into whole days, hours, and minutes, so the composable can pick the right plural string. */
data class UptimeComponents(val days: Int, val hours: Int, val minutes: Int)

/** Compact clock-drift unit, matching Foundation `Duration.UnitsFormat.abbreviated`. */
enum class DriftUnit { DAYS, HOURS, MINUTES, SECONDS }

/** A counted unit in a two-unit drift magnitude, e.g. 1 day + 5 hours. */
data class DriftUnitCount(val count: Long, val unit: DriftUnit)

/** Transmit/receive airtime as a fraction of uptime, as percentages, mirroring `airtimePercentDisplay`. */
data class AirtimePercents(val tx: Double, val rx: Double)

/**
 * Status-card "since last visit" caption buckets, mirroring iOS `NodeStatusViewModel.previousVisitLabel`.
 * The composable localizes minutes/hours and formats [CalendarDate] as month+day.
 */
sealed class PreviousVisitCaption {
    data class MinutesAgo(val minutes: Int) : PreviousVisitCaption()
    data class HoursAgo(val hours: Int) : PreviousVisitCaption()
    data class CalendarDate(val epochSeconds: Long) : PreviousVisitCaption()
}

/**
 * Locale-independent formatting for the node status readouts, mirroring the display computed properties on
 * the iOS `NodeStatusViewModel`. Kept a pure object so the numeric formatting is unit-tested on the desktop
 * JVM and never picks up a comma-decimal locale; the composable supplies the localized labels, units, and
 * uptime plurals.
 */
object NodeStatusFormat {
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L
    private const val MILLIVOLTS_PER_VOLT = 1000.0
    private const val PERCENT_SCALE = 100.0
    private const val GROUP_SIZE = 3

    /** A counter grouped in threes with `,` separators, e.g. `1,234,567`, matching the iOS grouped format. */
    fun count(value: Long): String {
        val digits = abs(value).toString()
        val grouped = buildString {
            digits.forEachIndexed { index, c ->
                if (index > 0 && (digits.length - index) % GROUP_SIZE == 0) append(',')
                append(c)
            }
        }
        return if (value < 0) "-$grouped" else grouped
    }

    /**
     * Compact clock-drift units, capped at two like iOS `Duration.UnitsFormat` `maximumUnitCount: 2`.
     * Zero drift still yields `0` seconds so the warning has a magnitude to interpolate.
     */
    fun driftMagnitudeUnits(seconds: Double): List<DriftUnitCount> {
        val total = seconds.toLong().coerceAtLeast(0)
        val days = total / SECONDS_PER_DAY
        val hours = (total % SECONDS_PER_DAY) / SECONDS_PER_HOUR
        val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val secs = total % SECONDS_PER_MINUTE
        val units = buildList {
            if (days > 0) add(DriftUnitCount(days, DriftUnit.DAYS))
            if (hours > 0) add(DriftUnitCount(hours, DriftUnit.HOURS))
            if (minutes > 0) add(DriftUnitCount(minutes, DriftUnit.MINUTES))
            if (secs > 0) add(DriftUnitCount(secs, DriftUnit.SECONDS))
        }
        return units.take(DRIFT_MAX_UNITS).ifEmpty { listOf(DriftUnitCount(0, DriftUnit.SECONDS)) }
    }

    private const val DRIFT_MAX_UNITS = 2

    /** Splits [seconds] into days/hours/minutes, dropping sub-minute remainders, as `formatDuration` does. */
    fun uptimeComponents(seconds: Long): UptimeComponents = UptimeComponents(
        days = (seconds / SECONDS_PER_DAY).toInt(),
        hours = ((seconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR).toInt(),
        minutes = ((seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE).toInt(),
    )

    /** Battery voltage from millivolts to three fraction digits, e.g. `3.850`. The unit is added by the UI. */
    fun voltage(millivolts: Int): String =
        fixed(millivolts / MILLIVOLTS_PER_VOLT, THREE_DECIMAL_SCALE, THREE_FRACTION_DIGITS)

    /** A signal reading to one fraction digit, e.g. `8.5`. */
    fun oneDecimal(value: Double): String = fixed(value, ONE_DECIMAL_SCALE, 1)

    /**
     * TX/RX airtime as a percentage of uptime, or null when uptime is zero (undefined ratio), matching the
     * iOS guard that blanks the row without an uptime denominator.
     */
    fun airtimePercents(status: StatusResponse): AirtimePercents? {
        if (status.uptime <= 0) return null
        val denominator = status.uptime.toDouble()
        return AirtimePercents(
            tx = status.airtime.toDouble() / denominator * PERCENT_SCALE,
            rx = status.rxAirtime.toDouble() / denominator * PERCENT_SCALE,
        )
    }

    /**
     * Picks the iOS previous-visit bucket. A future stamp clamps to zero minutes so the caption never
     * reads as a negative interval.
     */
    fun previousVisitCaption(previousTimestamp: Double, nowEpochSeconds: Double): PreviousVisitCaption {
        val interval = (nowEpochSeconds - previousTimestamp).coerceAtLeast(0.0)
        return when {
            interval < SECONDS_PER_HOUR ->
                PreviousVisitCaption.MinutesAgo((interval / SECONDS_PER_MINUTE).toInt())
            interval < SECONDS_PER_DAY ->
                PreviousVisitCaption.HoursAgo((interval / SECONDS_PER_HOUR).toInt())
            else -> PreviousVisitCaption.CalendarDate(previousTimestamp.toLong())
        }
    }

    private fun fixed(value: Double, scale: Long, fractionDigits: Int): String {
        val scaled = (value * scale).roundToLong()
        val whole = scaled / scale
        val fraction = abs(scaled % scale).toString().padStart(fractionDigits, '0')
        val sign = if (scaled < 0 && whole == 0L) "-" else ""
        return "$sign$whole.$fraction"
    }

    private const val ONE_DECIMAL_SCALE = 10L
    private const val THREE_DECIMAL_SCALE = 1000L
    private const val THREE_FRACTION_DIGITS = 3
}

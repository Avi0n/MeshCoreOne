package one.meshcore.shared.map

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong

/**
 * Locale-independent fixed decimals: half-up rounding, a `.` separator, and padded fraction digits.
 * Never a locale comma or scientific notation. Mirrors the formatting iOS services get from the POSIX
 * locale. A negative input keeps its sign even when the rounded magnitude is zero.
 */
internal fun formatFixedFraction(value: Double, fractionDigits: Int): String {
    val precision = decimalPrecision(fractionDigits)
    val scaled = (abs(value) * precision).roundToLong()
    val integerPart = scaled / precision
    val fraction = (scaled % precision).toString().padStart(fractionDigits, '0')
    val sign = if (value < 0) "-" else ""
    return "$sign$integerPart.$fraction"
}

/** Six fractional digits for Open-Meteo queries and six-digit coordinate readouts. */
internal fun formatFixed6(value: Double): String = formatFixedFraction(value, FIXED_6_FRACTION_DIGITS)

/** iOS `CLLocationCoordinate2D.formattedString`: six fractional digits, comma-separated. */
internal fun formatCoordinatePair(latitude: Double, longitude: Double): String =
    "${formatFixed6(latitude)}, ${formatFixed6(longitude)}"

/** Five decimal places, then [Double.toString], so trailing zeros drop. */
internal fun formatRounded5(value: Double): String {
    val scaled = round(value * ROUNDED_5_SCALE) / ROUNDED_5_SCALE
    return scaled.toString()
}

private fun decimalPrecision(fractionDigits: Int): Long {
    var precision = 1L
    repeat(fractionDigits) { precision *= DECIMAL_BASE }
    return precision
}

private const val DECIMAL_BASE = 10
private const val FIXED_6_FRACTION_DIGITS = 6
private const val ROUNDED_5_SCALE = 100_000.0

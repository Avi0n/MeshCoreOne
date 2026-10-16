package one.meshcore.shared.battery

import kotlin.math.round

internal data class BatteryCurveVertex(val xFraction: Double, val yFraction: Double, val volts: Double)

private const val PAD_MV = 100L
private const val MV_PER_TENTH = 100L
private const val MILLIVOLTS_PER_VOLT = 1000.0
private const val TENTHS_PER_VOLT = 10.0
private const val TENTHS_PER_HUNDREDTH = 10L
private const val HUNDREDTHS_PER_VOLT_INT = 100L
private const val HUNDREDTHS_PER_TENTH = 10
private const val FRACTION_PAD = 2
private const val EMPTY_MIN_VOLTS = 1.0
private const val EMPTY_MAX_VOLTS = 4.5
private const val EMPTY_MIN_HUNDREDTHS = 100L
private const val EMPTY_MAX_HUNDREDTHS = 450L
private const val PERCENT_SCALE = 100.0
private const val Y_MIDPOINT = 0.5
private const val X_TICK_COUNT = 5

/** Y domain in volts. The 100 mV pad rounds to 0.1 V, and a pad below zero stays negative. */
internal fun batteryCurveYDomain(ocvMillivolts: List<Int>): ClosedFloatingPointRange<Double> {
    if (ocvMillivolts.isEmpty()) return EMPTY_MIN_VOLTS..EMPTY_MAX_VOLTS
    val minTenths = floorToTenths(ocvMillivolts.min().toLong() - PAD_MV)
    val maxTenths = ceilToTenths(ocvMillivolts.max().toLong() + PAD_MV)
    return (minTenths / TENTHS_PER_VOLT)..(maxTenths / TENTHS_PER_VOLT)
}

/** One vertex per sample, in straight segments. Storage order is reversed so X runs from 0% to 100%. */
internal fun batteryCurveVertices(ocvMillivolts: List<Int>): List<BatteryCurveVertex> {
    if (ocvMillivolts.isEmpty()) return emptyList()
    val domain = batteryCurveYDomain(ocvMillivolts)
    val last = ocvMillivolts.lastIndex.toDouble()
    return ocvMillivolts.mapIndexed { index, millivolts ->
        val volts = millivolts / MILLIVOLTS_PER_VOLT
        val xFraction = if (last <= 0.0) 0.0 else (last - index) / last
        BatteryCurveVertex(
            xFraction = xFraction,
            yFraction = batteryCurveYFraction(volts, domain),
            volts = volts,
        )
    }.sortedBy { it.xFraction }
}

/** Fraction up from the rounded domain. A flat domain stays at mid-height. */
internal fun batteryCurveYFraction(volts: Double, domain: ClosedFloatingPointRange<Double>): Double {
    val span = domain.endInclusive - domain.start
    if (span <= 0.0) return Y_MIDPOINT
    return (volts - domain.start) / span
}

/** Percent ticks at 0, 25, 50, 75, and 100. */
internal fun batteryCurveXTicks(): List<Double> {
    val divisions = X_TICK_COUNT - 1
    return List(X_TICK_COUNT) { index -> index.toDouble() / divisions.toDouble() }
}

/**
 * Y labels in hundredths of a volt, including both rounded ends.
 * Integer hundredths keep a tenth from becoming 4.29 through binary rounding.
 */
internal fun batteryCurveYTickHundredths(ocvMillivolts: List<Int>): List<Long> {
    val minHundredths: Long
    val maxHundredths: Long
    if (ocvMillivolts.isEmpty()) {
        minHundredths = EMPTY_MIN_HUNDREDTHS
        maxHundredths = EMPTY_MAX_HUNDREDTHS
    } else {
        val minTenths = floorToTenths(ocvMillivolts.min().toLong() - PAD_MV)
        val maxTenths = ceilToTenths(ocvMillivolts.max().toLong() + PAD_MV)
        minHundredths = minTenths * TENTHS_PER_HUNDREDTH
        maxHundredths = maxTenths * TENTHS_PER_HUNDREDTH
    }
    if (maxHundredths == minHundredths) return listOf(minHundredths)
    return listOf(minHundredths, (minHundredths + maxHundredths) / 2, maxHundredths)
}

internal fun batteryPercentLabel(xFraction: Double): String = round(xFraction * PERCENT_SCALE).toInt().toString()

/** A volt label from integer hundredths, so a negative tenth does not truncate toward zero. */
internal fun batteryVoltLabel(hundredths: Long): String {
    val negative = hundredths < 0
    val absolute = if (negative) -hundredths else hundredths
    val whole = absolute / HUNDREDTHS_PER_VOLT_INT
    val fraction = (absolute % HUNDREDTHS_PER_VOLT_INT).toInt()
    val body = if (fraction % HUNDREDTHS_PER_TENTH == 0) {
        "$whole.${fraction / HUNDREDTHS_PER_TENTH}"
    } else {
        "$whole.${fraction.toString().padStart(FRACTION_PAD, '0')}"
    }
    return if (negative) "-$body" else body
}

/** Truncating division rounds a negative remainder the wrong way, so floor it explicitly. */
private fun floorToTenths(millivolts: Long): Long {
    val quotient = millivolts / MV_PER_TENTH
    val remainder = millivolts % MV_PER_TENTH
    return if (remainder != 0L && millivolts < 0L) quotient - 1 else quotient
}

private fun ceilToTenths(millivolts: Long): Long {
    val quotient = floorToTenths(millivolts)
    val remainder = millivolts - quotient * MV_PER_TENTH
    return if (remainder == 0L) quotient else quotient + 1
}

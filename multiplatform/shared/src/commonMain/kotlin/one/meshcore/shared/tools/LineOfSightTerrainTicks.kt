package one.meshcore.shared.tools

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * iOS `TerrainProfileCanvas.niceStep`: snap a raw range/divisions step onto 1, 2, 2.5, 5, or 10
 * times a power of ten so axis ticks stay round.
 */
fun terrainNiceStep(range: Double, targetDivisions: Int): Double {
    if (range <= 0.0 || targetDivisions <= 0) return 1.0
    val roughStep = range / targetDivisions
    val magnitude = 10.0.pow(floor(log10(roughStep)))
    val normalized = roughStep / magnitude
    val niceNormalized = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= TWO_AND_HALF_STEP -> TWO_AND_HALF_STEP
        normalized <= FIVE_STEP -> FIVE_STEP
        else -> TEN_STEP
    }
    return niceNormalized * magnitude
}

/** iOS `tickValues(for:step:)`: ticks from the first multiple of [step] at or above [lower]. */
fun terrainTickValues(lower: Double, upper: Double, step: Double): List<Double> {
    if (step <= 0.0) return emptyList()
    var current = ceil(lower / step) * step
    val ticks = mutableListOf<Double>()
    while (current <= upper) {
        ticks.add(current)
        current += step
    }
    return ticks
}

/** Last Y tick carries the meter unit, matching iOS `drawGrid`. */
fun terrainElevationTickLabel(meters: Double, isLast: Boolean): String {
    val number = meters.toInt().toString()
    return if (isLast) "$number m" else number
}

/**
 * X ticks are kilometres. Whole-km steps drop the decimal; the last tick carries `km`, matching
 * iOS `drawGrid`.
 */
fun terrainDistanceTickLabel(meters: Double, stepMeters: Double, isLast: Boolean): String {
    val km = meters / METERS_PER_KM
    val wholeKm = stepMeters >= METERS_PER_KM && abs(km - km.toInt()) < WHOLE_KM_EPSILON
    val number = if (wholeKm) km.toInt().toString() else oneDecimalKm(km)
    return if (isLast) "$number km" else number
}

private fun oneDecimalKm(km: Double): String {
    val scaled = (km * ONE_DECIMAL_SCALE).roundToInt() / ONE_DECIMAL_SCALE
    return scaled.toString()
}

private const val METERS_PER_KM = 1000.0
private const val ONE_DECIMAL_SCALE = 10.0
private const val WHOLE_KM_EPSILON = 1e-9
private const val TWO_AND_HALF_STEP = 2.5
private const val FIVE_STEP = 5.0
private const val TEN_STEP = 10.0

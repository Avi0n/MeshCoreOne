package one.meshcore.shared.map

import one.meshcore.shared.remotenode.LocalizedLengthSymbols
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Whether a BCP-47 region uses metric map scales. US, Liberia, Myanmar, and GB follow MapKit's
 * imperial scale; everywhere else is SI.
 */
fun localeUsesMetric(region: String): Boolean = region.uppercase() !in IMPERIAL_SCALE_REGIONS

/**
 * Picks a 1/2/5 × 10^n bar that covers about a quarter of [region]'s visible width. Null when the
 * span is empty or the cosine at the center latitude is degenerate.
 */
fun mapScaleBarModel(
    region: MapCameraRegion,
    useMetric: Boolean,
    symbols: LocalizedLengthSymbols = LocalizedLengthSymbols.LATIN,
): MapScaleBarModel? {
    val viewportMeters = visibleWidthMeters(region) ?: return null
    if (viewportMeters < MIN_VIEWPORT_METERS) return null
    return if (useMetric) {
        metricScale(viewportMeters, symbols)
    } else {
        imperialScale(viewportMeters, symbols)
    }
}

private fun visibleWidthMeters(region: MapCameraRegion): Double? {
    if (!region.spanLonDeg.isFinite() || region.spanLonDeg <= 0.0) return null
    if (!region.center.latitude.isFinite()) return null
    val latRad = region.center.latitude * PI / DEGREES_PER_HALF_TURN
    val meters = region.spanLonDeg * METERS_PER_DEGREE * abs(cos(latRad))
    return meters.takeIf { it.isFinite() && it > 0.0 }
}

private fun metricScale(viewportMeters: Double, symbols: LocalizedLengthSymbols): MapScaleBarModel? {
    val nice = niceNumberAtMost(viewportMeters * TARGET_FRACTION) ?: return null
    val fraction = (nice / viewportMeters).toFloat()
    if (fraction <= 0f) return null
    val label = if (nice >= METERS_PER_KM) {
        "${formatWhole(nice / METERS_PER_KM)} ${symbols.kilometer}"
    } else {
        "${formatWhole(nice)} ${symbols.meter}"
    }
    return MapScaleBarModel(label = label, fractionOfViewport = fraction)
}

private fun imperialScale(viewportMeters: Double, symbols: LocalizedLengthSymbols): MapScaleBarModel? {
    val viewportMiles = viewportMeters / METERS_PER_MILE
    val useMiles = viewportMiles * TARGET_FRACTION >= 1.0
    val viewport = if (useMiles) viewportMiles else viewportMeters / METERS_PER_FOOT
    val unit = if (useMiles) symbols.mile else symbols.foot
    val nice = niceNumberAtMost(viewport * TARGET_FRACTION) ?: return null
    val fraction = (nice / viewport).toFloat()
    return if (fraction > 0f) {
        MapScaleBarModel(label = "${formatWhole(nice)} $unit", fractionOfViewport = fraction)
    } else {
        null
    }
}

/** Largest 1/2/5 × 10^n that does not exceed [max]. */
internal fun niceNumberAtMost(max: Double): Double? {
    if (max <= 0.0 || !max.isFinite()) return null
    var nice = niceNumber(max)
    if (nice > max) {
        nice = niceNumber(max / 2.0)
    }
    return nice.takeIf { it > 0.0 && it <= max }
}

private fun niceNumber(raw: Double): Double {
    val exp = 10.0.pow(floor(log10(raw)))
    val mantissa = raw / exp
    val niceMantissa = when {
        mantissa < ONE_TO_TWO_CUTOFF -> 1.0
        mantissa < TWO_TO_FIVE_CUTOFF -> 2.0
        mantissa < FIVE_TO_TEN_CUTOFF -> FIVE_MANTISSA
        else -> TEN_MANTISSA
    }
    return niceMantissa * exp
}

private fun formatWhole(value: Double): String {
    val asLong = value.toLong()
    return if (value == asLong.toDouble()) asLong.toString() else value.toString()
}

private val IMPERIAL_SCALE_REGIONS = setOf("US", "LR", "MM", "GB")
private const val METERS_PER_DEGREE = 111_320.0
private const val METERS_PER_KM = 1000.0
private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_FOOT = 0.3048
private const val TARGET_FRACTION = 1.0 / 3.0
private const val DEGREES_PER_HALF_TURN = 180.0
private const val ONE_TO_TWO_CUTOFF = 1.5
private const val TWO_TO_FIVE_CUTOFF = 3.5
private const val FIVE_TO_TEN_CUTOFF = 7.5
private const val FIVE_MANTISSA = 5.0
private const val TEN_MANTISSA = 10.0
private const val MIN_VIEWPORT_METERS = 1.0

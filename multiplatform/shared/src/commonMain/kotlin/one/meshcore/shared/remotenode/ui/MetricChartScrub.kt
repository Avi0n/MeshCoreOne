package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.TelemetryChartPoint
import kotlin.math.abs

/**
 * Shared time window and value domain for every series on one chart. A caller domain
 * replaces the sample extrema; an empty plot has no scale.
 */
internal data class MetricChartScale(
    val timeStart: Double,
    val timeEnd: Double,
    val valueMin: Double,
    val valueMax: Double,
)

internal fun metricChartScale(
    primary: List<TelemetryChartPoint>,
    secondary: List<TelemetryChartPoint>,
    valueDomain: ClosedRange<Double>?,
): MetricChartScale? {
    val extremes = ScaleExtremes()
    val trackValues = valueDomain == null
    extremes.include(primary, trackValues)
    extremes.include(secondary, trackValues)
    if (extremes.count == 0) return null
    return MetricChartScale(
        timeStart = extremes.timeStart,
        timeEnd = extremes.timeEnd,
        valueMin = valueDomain?.start ?: extremes.valueMin,
        valueMax = valueDomain?.endInclusive ?: extremes.valueMax,
    )
}

private class ScaleExtremes {
    var timeStart = Double.POSITIVE_INFINITY
    var timeEnd = Double.NEGATIVE_INFINITY
    var valueMin = Double.POSITIVE_INFINITY
    var valueMax = Double.NEGATIVE_INFINITY
    var count = 0

    fun include(points: List<TelemetryChartPoint>, trackValues: Boolean) {
        for (index in points.indices) {
            val point = points[index]
            count += 1
            val time = point.timestampSeconds
            if (time < timeStart) timeStart = time
            if (time > timeEnd) timeEnd = time
            if (!trackValues) continue
            val value = point.value
            if (value < valueMin) valueMin = value
            if (value > valueMax) valueMax = value
        }
    }
}

/** Fractions of [points] across their own time window. One instant stays at the start. */
internal fun metricChartXFractions(points: List<TelemetryChartPoint>): List<Double> {
    if (points.isEmpty()) return emptyList()
    val start = points.minOf { it.timestampSeconds }
    val end = points.maxOf { it.timestampSeconds }
    return points.map { metricChartXFraction(it.timestampSeconds, start, end) }
}

/** Fraction of [timestamp] inside [start]..[end]. A zero span does not stretch the sample. */
internal fun metricChartXFraction(timestamp: Double, start: Double, end: Double): Double {
    val span = end - start
    if (span <= 0.0) return 0.0
    return ((timestamp - start) / span).coerceIn(0.0, 1.0)
}

/**
 * Fraction up from the bottom of the value domain. A sample outside the domain stays
 * outside 0..1 so the plot clips it instead of pinning it to the edge.
 */
internal fun metricChartYFraction(value: Double, domainMin: Double, domainMax: Double): Double {
    val span = domainMax - domainMin
    if (span <= 0.0) return Y_MIDPOINT
    return (value - domainMin) / span
}

/**
 * The scrub snapped to the nearest plotted time. Each series then contributes its own
 * nearest sample, and the rule sits on the snapped time rather than the finger.
 */
internal data class MetricChartScrub(
    val snappedTimestamp: Double,
    val xFraction: Double,
    val primary: TelemetryChartPoint?,
    val secondary: TelemetryChartPoint?,
)

internal fun metricChartScrub(
    touchFraction: Double,
    primary: List<TelemetryChartPoint>,
    secondary: List<TelemetryChartPoint>,
): MetricChartScrub? {
    val scale = metricChartScale(primary, secondary, valueDomain = null) ?: return null
    val span = scale.timeEnd - scale.timeStart
    val touchTime = if (span <= 0.0) {
        scale.timeStart
    } else {
        scale.timeStart + touchFraction.coerceIn(0.0, 1.0) * span
    }
    val primaryPick = nearestAt(primary, touchTime)
    val secondaryPick = nearestAt(secondary, touchTime)
    val snapped = when {
        primaryPick == null -> secondaryPick?.timestampSeconds
        secondaryPick == null -> primaryPick.timestampSeconds
        abs(primaryPick.timestampSeconds - touchTime) <= abs(secondaryPick.timestampSeconds - touchTime) ->
            primaryPick.timestampSeconds
        else -> secondaryPick.timestampSeconds
    } ?: return null
    return MetricChartScrub(
        snappedTimestamp = snapped,
        xFraction = metricChartXFraction(snapped, scale.timeStart, scale.timeEnd),
        primary = nearestAt(primary, snapped),
        secondary = nearestAt(secondary, snapped),
    )
}

private fun nearestAt(points: List<TelemetryChartPoint>, timestamp: Double): TelemetryChartPoint? {
    if (points.isEmpty()) return null
    return points.minBy { abs(it.timestampSeconds - timestamp) }
}

private const val Y_MIDPOINT = 0.5
private const val X_TICK_COUNT = 4

/** Four fractions across the window. A zero span stays at the start instead of inventing a width. */
internal fun metricChartXTicks(timeStart: Double, timeEnd: Double): List<Double> {
    if (timeEnd - timeStart <= 0.0) return listOf(0.0)
    val divisions = X_TICK_COUNT - 1
    return List(X_TICK_COUNT) { index -> index.toDouble() / divisions.toDouble() }
}

/** Instants for those fractions. A zero span repeats the only instant once. */
internal fun metricChartXTickInstants(timeStart: Double, timeEnd: Double): List<Double> {
    val span = timeEnd - timeStart
    return metricChartXTicks(timeStart, timeEnd).map { fraction ->
        if (span <= 0.0) timeStart else timeStart + fraction * span
    }
}

/** Value ticks that include both ends of the domain. A flat domain keeps that one value. */
internal fun metricChartYTicks(domainMin: Double, domainMax: Double): List<Double> {
    if (domainMax - domainMin <= 0.0) return listOf(domainMin)
    return listOf(domainMin, (domainMin + domainMax) / 2.0, domainMax)
}

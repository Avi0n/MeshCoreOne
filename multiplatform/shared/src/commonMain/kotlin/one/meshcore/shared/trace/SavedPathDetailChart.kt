package one.meshcore.shared.trace

import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.remotenode.ui.metricChartXTicks
import one.meshcore.shared.remotenode.ui.metricChartYTicks

/** Horizontal placement by run date. One instant is not stretched across the width. */
fun savedPathDetailXFractions(dates: List<Double>): List<Double> {
    if (dates.isEmpty()) return emptyList()
    val start = dates.min()
    val end = dates.max()
    val span = end - start
    return if (span <= 0.0) {
        List(dates.size) { 0.0 }
    } else {
        dates.map { ((it - start) / span).coerceIn(0.0, 1.0) }
    }
}

/**
 * Polyline for the detail chart. X is the run date and a larger round trip sits higher.
 * The list sparkline keeps only the latest samples; this chart includes every run it is given.
 */
fun savedPathDetailPoints(runs: List<TracePathRunDto>, width: Double, height: Double): List<SparklinePoint> {
    if (runs.isEmpty()) return emptyList()
    val xs = savedPathDetailXFractions(runs.map { it.date })
    val rtts = runs.map { it.roundTripMs.toDouble() }
    val min = rtts.min()
    val max = rtts.max()
    val span = max - min
    return runs.indices.map { index ->
        val y = if (span > 0.0) height - (rtts[index] - min) / span * height else height / 2.0
        SparklinePoint(x = xs[index] * width, y = y)
    }
}

/** Date-axis fractions for the detail chart. One instant stays a single tick. */
fun savedPathDetailXTickFractions(dates: List<Double>): List<Double> {
    if (dates.isEmpty()) return emptyList()
    return metricChartXTicks(dates.min(), dates.max())
}

/** Round-trip ticks that include the slowest and fastest plotted run. */
fun savedPathDetailRttTicks(roundTripsMs: List<Int>): List<Double> {
    if (roundTripsMs.isEmpty()) return emptyList()
    return metricChartYTicks(roundTripsMs.min().toDouble(), roundTripsMs.max().toDouble())
}

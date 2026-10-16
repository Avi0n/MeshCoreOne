package one.meshcore.shared.trace

/**
 * Pure data shapes for the results comparison row and the recent-runs sparkline. The drawing itself is a
 * Compose concern; the geometry here is portable math pinned to the Swift `ComparisonRowView` and
 * `MiniSparkline`.
 */

/** Whether the current run was slower, faster, or unchanged versus the compared run. */
enum class RttTrend { INCREASED, DECREASED, UNCHANGED }

/**
 * Round-trip comparison of the current run against a previous one. [diffMs] is `current - previous`;
 * [percentChange] is `diff / previous * 100` (0 when the previous run was 0 ms). Mirrors the arithmetic
 * in Swift `ComparisonRowView`.
 */
data class RttComparison(val diffMs: Int, val percentChange: Double, val trend: RttTrend)

/** Compare [currentMs] against [previousMs], producing the diff, percent change, and trend direction. */
fun rttComparison(currentMs: Int, previousMs: Int): RttComparison {
    val diff = currentMs - previousMs
    val pct = if (previousMs > 0) diff.toDouble() / previousMs * PERCENT_SCALE else 0.0
    val trend = when {
        diff > 0 -> RttTrend.INCREASED
        diff < 0 -> RttTrend.DECREASED
        else -> RttTrend.UNCHANGED
    }
    return RttComparison(diff, pct, trend)
}

/** A point in a sparkline's local canvas coordinate space (origin top-left, Y growing downward). */
data class SparklinePoint(val x: Double, val y: Double)

/**
 * Polyline points for a sparkline of [values] in a [width] x [height] canvas. When the values span a
 * range, each point is `x = i * width / max(count - 1, 1)` and `y = height - (v - min) / (max - min) *
 * height` (Y inverted so larger values sit higher). An empty series, a single autoscaled sample, and a
 * constant series draw a flat line at `height / 2` across the full width, matching Swift `MiniSparkline`
 * when the maximum is not greater than the minimum.
 */
fun sparklinePoints(
    values: List<Int>,
    width: Double,
    height: Double,
    yMin: Double? = null,
    yMax: Double? = null,
): List<SparklinePoint> = sparklinePointsFromDoubles(values.map { it.toDouble() }, width, height, yMin, yMax)

/**
 * Same layout as [sparklinePoints] for floating-point series (telemetry, SNR, battery volts).
 * Pass [yMin] and [yMax] together to lock the vertical scale (noise floor −130…−60 dBm). A locked
 * single sample stays on that scale instead of collapsing to the mid line.
 */
fun sparklinePointsFromDoubles(
    values: List<Double>,
    width: Double,
    height: Double,
    yMin: Double? = null,
    yMax: Double? = null,
): List<SparklinePoint> {
    if (drawsFlatMidLine(values, yMin, yMax)) {
        val y = height / 2
        return listOf(SparklinePoint(0.0, y), SparklinePoint(width, y))
    }
    val min = yMin ?: values.min()
    val max = yMax ?: values.max()
    val stepX = width / maxOf(values.size - 1, 1)
    if (min < max) {
        val span = max - min
        return values.mapIndexed { index, value ->
            val y = height - (value - min) / span * height
            SparklinePoint(x = index * stepX, y = y)
        }
    }
    return values.mapIndexed { index, _ -> SparklinePoint(x = index * stepX, y = height / 2) }
}

private fun drawsFlatMidLine(values: List<Double>, yMin: Double?, yMax: Double?): Boolean {
    if (values.isEmpty()) return true
    return values.size == 1 && yMin == null && yMax == null
}

private const val PERCENT_SCALE = 100.0

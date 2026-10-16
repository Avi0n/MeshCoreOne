package one.meshcore.shared.tools.ui

import androidx.compose.ui.unit.dp
import io.github.koalaplot.core.xygraph.DoubleLinearAxisModel
import io.github.koalaplot.core.xygraph.Point
import one.meshcore.shared.tools.NoiseFloorReading
import one.meshcore.shared.tools.noiseFloorChartDomain
import kotlin.math.abs
import kotlin.math.ceil

internal fun noiseFloorChartSeries(readings: List<NoiseFloorReading>, startEpochMs: Long): List<Point<Double, Double>> =
    readings.map {
        Point(
            (it.timestampEpochMs.toDouble() - startEpochMs.toDouble()) / MILLIS_PER_MINUTE,
            it.noiseFloor.toDouble(),
        )
    }

internal fun noiseFloorChartMinuteDomain(
    readings: List<NoiseFloorReading>,
    startEpochMs: Long,
): ClosedFloatingPointRange<Double> {
    val domain = noiseFloorChartDomain(readings, startEpochMs)
    return (domain.start / MILLIS_PER_MINUTE)..(domain.endInclusive / MILLIS_PER_MINUTE)
}

private const val MILLIS_PER_MINUTE = 60_000.0

internal fun noiseFloorChartTimeAxis(domain: ClosedFloatingPointRange<Double>) = DoubleLinearAxisModel(
    domain,
    minimumMajorTickIncrement = MINUTE_TICK_INCREMENT,
    minimumMajorTickSpacing = MINUTE_TICK_SPACING.dp,
    minorTickCount = NO_MINOR_TICKS,
)

internal fun noiseFloorChartLabelStride(
    axis: DoubleLinearAxisModel,
    ticks: List<Double>,
    requiredWidth: Float,
    plotWidth: Int,
): Int {
    val spacing = ticks.zipWithNext { left, right -> abs(axis.computeOffset(right) - axis.computeOffset(left)) }
        .minOrNull()?.times(plotWidth) ?: return MIN_LABEL_STRIDE
    if (spacing <= 0) return MIN_LABEL_STRIDE
    return ceil(requiredWidth / spacing).toInt().coerceAtLeast(MIN_LABEL_STRIDE)
}

private const val MINUTE_TICK_INCREMENT = 1.0
private const val MINUTE_TICK_SPACING = 1
private const val NO_MINOR_TICKS = 0
private const val MIN_LABEL_STRIDE = 1

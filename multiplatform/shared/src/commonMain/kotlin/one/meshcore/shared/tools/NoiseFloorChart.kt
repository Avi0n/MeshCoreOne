package one.meshcore.shared.tools

internal const val NOISE_CHART_WINDOW_MS = 300_000.0
internal const val NOISE_CHART_Y_MIN_DBM = -130.0
internal const val NOISE_CHART_Y_MAX_DBM = -60.0

/** The retained origin and latest sample determine the chart domain, including hidden gaps. */
internal fun noiseFloorChartDomain(
    readings: List<NoiseFloorReading>,
    startEpochMs: Long,
): ClosedFloatingPointRange<Double> {
    val latest = readings.lastOrNull()?.let { elapsedMillis(it, startEpochMs) }?.coerceAtLeast(0.0) ?: 0.0
    val end = maxOf(NOISE_CHART_WINDOW_MS, latest)
    return (end - NOISE_CHART_WINDOW_MS)..end
}

private fun elapsedMillis(reading: NoiseFloorReading, startEpochMs: Long): Double =
    reading.timestampEpochMs.toDouble() - startEpochMs.toDouble()

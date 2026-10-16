package one.meshcore.shared.tools

import one.meshcore.shared.model.Uuid

/** One noise-floor sample. Mirrors iOS `NoiseFloorReading`. */
data class NoiseFloorReading(
    val id: Uuid,
    val timestampEpochMs: Long,
    val noiseFloor: Int,
    val lastRssi: Int,
    val lastSnr: Double,
)

/** Min/max/avg over a reading series. Mirrors iOS `NoiseFloorStatistics`. */
data class NoiseFloorStatistics(val min: Int, val max: Int, val average: Double)

/** Quality band from noise-floor dBm thresholds (iOS `NoiseFloorQuality.from`). */
enum class NoiseFloorQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    UNKNOWN,
    ;

    companion object {
        fun from(noiseFloor: Int): NoiseFloorQuality = when {
            noiseFloor <= EXCELLENT_MAX -> EXCELLENT
            noiseFloor <= GOOD_MAX -> GOOD
            noiseFloor <= FAIR_MAX -> FAIR
            else -> POOR
        }

        private const val EXCELLENT_MAX = -100
        private const val GOOD_MAX = -90
        private const val FAIR_MAX = -80
    }
}

/**
 * Trend over the series. Each half is an integer mean, an odd middle sample is in neither half,
 * and a 3 dBm shift stays stable. The spoken chart label uses this.
 */
enum class NoiseFloorTrend {
    STABLE,
    INCREASING,
    DECREASING,
}

const val NOISE_FLOOR_MAX_READINGS: Int = 200
const val NOISE_FLOOR_POLL_INTERVAL_MS: Long = 1500L
private const val TREND_MIN_SAMPLES = 4
private const val TREND_DELTA_THRESHOLD = 3

fun noiseFloorStatistics(readings: List<NoiseFloorReading>): NoiseFloorStatistics? {
    if (readings.isEmpty()) return null
    val values = readings.map { it.noiseFloor }
    return NoiseFloorStatistics(
        min = values.min(),
        max = values.max(),
        average = values.sum().toDouble() / values.size,
    )
}

fun noiseFloorTrend(readings: List<NoiseFloorReading>): NoiseFloorTrend {
    if (readings.size < TREND_MIN_SAMPLES) return NoiseFloorTrend.STABLE
    val half = readings.size / 2
    val firstAvg = integerMean(readings.take(half))
    val secondAvg = integerMean(readings.takeLast(half))
    return when {
        secondAvg > firstAvg + TREND_DELTA_THRESHOLD -> NoiseFloorTrend.INCREASING
        secondAvg < firstAvg - TREND_DELTA_THRESHOLD -> NoiseFloorTrend.DECREASING
        else -> NoiseFloorTrend.STABLE
    }
}

/** Truncating mean. The sum is a [Long] so a full retained series of large samples cannot wrap. */
private fun integerMean(readings: List<NoiseFloorReading>): Int {
    if (readings.isEmpty()) return 0
    return (readings.sumOf { it.noiseFloor.toLong() } / readings.size).toInt()
}

/**
 * The spoken summary of the noise-floor chart: a sparkline carries no text, so the series is reduced to
 * the same five facts iOS reads out (count, min, max, average, trend). Average is truncated, matching the
 * `Int(stats.average)` conversion in the Swift chart label so both platforms speak the same number.
 */
data class NoiseFloorChartSummary(
    val count: Int,
    val min: Int,
    val max: Int,
    val average: Int,
    val trend: NoiseFloorTrend,
)

/** The chart summary for [readings], or `null` when there is nothing to describe (the empty label). */
fun noiseFloorChartSummary(readings: List<NoiseFloorReading>): NoiseFloorChartSummary? {
    val stats = noiseFloorStatistics(readings) ?: return null
    return NoiseFloorChartSummary(
        count = readings.size,
        min = stats.min,
        max = stats.max,
        average = stats.average.toInt(),
        trend = noiseFloorTrend(readings),
    )
}

/** Appends [reading], keeping at most [maxReadings] newest-last (chronological). */
fun appendNoiseFloorReading(
    readings: List<NoiseFloorReading>,
    reading: NoiseFloorReading,
    maxReadings: Int = NOISE_FLOOR_MAX_READINGS,
): List<NoiseFloorReading> = (readings + reading).takeLast(maxReadings)

fun newNoiseFloorReadingId(): Uuid = Uuid.random()

/**
 * Spoken current-reading label: "N dBm, quality", or [noReading] when the live sample is missing.
 * Matches iOS `CurrentReadingSection.accessibilityLabel`.
 */
fun currentReadingAccessibilityLabel(noiseFloor: Int?, dbm: String, qualityLabel: String, noReading: String): String {
    val value = noiseFloor ?: return noReading
    return "$value $dbm, $qualityLabel"
}

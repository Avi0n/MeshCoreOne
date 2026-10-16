package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The round-trip sparkline is a canvas with no text of its own, so the trend is the only thing that
 * describes it to a screen reader. Thresholds and halving mirror the iOS `sparklineAccessibilityLabel`.
 */
class SparklineDescriptionTest {
    @Test
    fun meanPreservesIntExtremaAndTruncatesMixedSignsTowardZero() {
        assertEquals(1_073_741_824, meanRoundTripMs(listOf(1, Int.MAX_VALUE)))
        assertEquals(Int.MAX_VALUE, meanRoundTripMs(List(3) { Int.MAX_VALUE }))
        assertEquals(Int.MIN_VALUE, meanRoundTripMs(List(3) { Int.MIN_VALUE }))
        assertEquals(0, meanRoundTripMs(listOf(Int.MIN_VALUE, Int.MAX_VALUE)))
        assertEquals(-1, meanRoundTripMs(listOf(-1, -2)))
        assertNull(meanRoundTripMs(emptyList()))
    }

    @Test
    fun exactThresholdAndOneBeyondStayCorrectNearBothIntBounds() {
        assertEquals(SparklineTrend.STABLE, sparklineTrend(listOf(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertEquals(SparklineTrend.STABLE, sparklineTrend(listOf(Int.MAX_VALUE - 50, Int.MAX_VALUE)))
        assertEquals(SparklineTrend.INCREASING, sparklineTrend(listOf(Int.MAX_VALUE - 51, Int.MAX_VALUE)))
        assertEquals(SparklineTrend.STABLE, sparklineTrend(listOf(Int.MIN_VALUE + 50, Int.MIN_VALUE)))
        assertEquals(SparklineTrend.DECREASING, sparklineTrend(listOf(Int.MIN_VALUE + 51, Int.MIN_VALUE)))
    }

    @Test
    fun nearMaximumSamplesDoNotOverflowTheTrendThreshold() {
        assertEquals(SparklineTrend.STABLE, sparklineTrend(listOf(Int.MAX_VALUE - 20, Int.MAX_VALUE)))
        assertEquals(
            SparklineTrend.DECREASING,
            sparklineTrend(listOf(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1)),
        )
    }

    @Test
    fun aRiseBeyondTheThresholdReadsAsIncreasing() {
        val rtts = listOf(100, 100, 100 + TREND_THRESHOLD_MS + 1, 100 + TREND_THRESHOLD_MS + 1)

        assertEquals(SparklineTrend.INCREASING, sparklineTrend(rtts))
    }

    @Test
    fun aFallBeyondTheThresholdReadsAsDecreasing() {
        val rtts = listOf(100 + TREND_THRESHOLD_MS + 1, 100 + TREND_THRESHOLD_MS + 1, 100, 100)

        assertEquals(SparklineTrend.DECREASING, sparklineTrend(rtts))
    }

    /** Run-to-run noise on a LoRa path must not flip the spoken trend on every trace. */
    @Test
    fun aChangeWithinTheThresholdStaysStable() {
        val rtts = listOf(100, 100, 100 + TREND_THRESHOLD_MS, 100 + TREND_THRESHOLD_MS)

        assertEquals(SparklineTrend.STABLE, sparklineTrend(rtts))
    }

    @Test
    fun tooFewSamplesHaveNoDirection() {
        assertEquals(SparklineTrend.STABLE, sparklineTrend(emptyList()))
        assertEquals(SparklineTrend.STABLE, sparklineTrend(listOf(500)))
    }

    /** An odd count halves down, so the middle sample belongs to neither half — as on iOS. */
    @Test
    fun anOddCountComparesTheOuterHalves() {
        val rtts = listOf(100, 100, 9_999, 400, 400)

        assertEquals(SparklineTrend.INCREASING, sparklineTrend(rtts))
    }

    @Test
    fun everyTrendHasItsOwnWord() {
        val trends = SparklineTrend.entries
        assertEquals(trends.size, trends.map { it.wordResource() }.toSet().size)
    }
}

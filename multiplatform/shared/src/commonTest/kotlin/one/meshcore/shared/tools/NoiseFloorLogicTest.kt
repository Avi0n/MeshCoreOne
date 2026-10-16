package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class NoiseFloorLogicTest {
    @Test
    fun qualityThresholds() {
        assertEquals(NoiseFloorQuality.EXCELLENT, NoiseFloorQuality.from(-105))
        assertEquals(NoiseFloorQuality.EXCELLENT, NoiseFloorQuality.from(-100))
        assertEquals(NoiseFloorQuality.GOOD, NoiseFloorQuality.from(-95))
        assertEquals(NoiseFloorQuality.FAIR, NoiseFloorQuality.from(-85))
        assertEquals(NoiseFloorQuality.POOR, NoiseFloorQuality.from(-70))
    }

    @Test
    fun statisticsAndAppendCap() {
        assertNull(noiseFloorStatistics(emptyList()))
        val a = reading(noise = -100)
        val b = reading(noise = -90)
        val stats = noiseFloorStatistics(listOf(a, b))!!
        assertEquals(-100, stats.min)
        assertEquals(-90, stats.max)
        assertEquals(-95.0, stats.average)

        var list = emptyList<NoiseFloorReading>()
        repeat(5) { i -> list = appendNoiseFloorReading(list, reading(noise = -i), maxReadings = 3) }
        assertEquals(3, list.size)
        assertEquals(-2, list.first().noiseFloor)
        assertEquals(-4, list.last().noiseFloor)
    }

    @Test
    fun trendDetectsShift() {
        val rising = (0 until 6).map { reading(noise = -100 + it * 2) }
        assertEquals(NoiseFloorTrend.INCREASING, noiseFloorTrend(rising))
        val falling = (0 until 6).map { reading(noise = -80 - it * 2) }
        assertEquals(NoiseFloorTrend.DECREASING, noiseFloorTrend(falling))
        assertEquals(NoiseFloorTrend.STABLE, noiseFloorTrend(listOf(reading(-90), reading(-90))))
    }

    @Test
    fun trendUsesIntegerHalfMeansAndIgnoresAnOddMiddleSample() {
        // A 3 dBm integer shift stays stable. A 4 dBm shift does not.
        assertEquals(
            NoiseFloorTrend.STABLE,
            noiseFloorTrend(listOf(reading(-100), reading(-100), reading(-97), reading(-97))),
        )
        assertEquals(
            NoiseFloorTrend.INCREASING,
            noiseFloorTrend(listOf(reading(-100), reading(-100), reading(-96), reading(-96))),
        )
        assertEquals(
            NoiseFloorTrend.DECREASING,
            noiseFloorTrend(listOf(reading(-96), reading(-96), reading(-100), reading(-100))),
        )
        // The middle sample is in neither half, so it cannot flip the trend.
        assertEquals(
            NoiseFloorTrend.STABLE,
            noiseFloorTrend(
                listOf(reading(-100), reading(-100), reading(-50), reading(-100), reading(-100)),
            ),
        )
        assertEquals(NoiseFloorTrend.STABLE, noiseFloorTrend(emptyList()))
        assertEquals(
            NoiseFloorTrend.STABLE,
            noiseFloorTrend(listOf(reading(-130), reading(-60), reading(-60))),
        )
        val dense = (0 until 200).map { index -> reading(noise = if (index < 100) -100 else -90) }
        assertEquals(NoiseFloorTrend.INCREASING, noiseFloorTrend(dense))
    }

    @Test
    fun trendHalfMeansDoNotOverflowInt() {
        val huge = 2_000_000_000
        assertEquals(
            NoiseFloorTrend.STABLE,
            noiseFloorTrend(
                listOf(reading(huge), reading(huge), reading(huge + 3), reading(huge + 3)),
            ),
        )
    }

    @Test
    fun currentReadingLabelJoinsValueUnitAndQuality() {
        assertEquals(
            "-100 dBm, Excellent",
            currentReadingAccessibilityLabel(-100, "dBm", "Excellent", "No reading available"),
        )
        assertEquals(
            "No reading available",
            currentReadingAccessibilityLabel(null, "dBm", "Excellent", "No reading available"),
        )
    }

    @Test
    fun chartSummaryDescribesTheSeries() {
        assertNull(noiseFloorChartSummary(emptyList()), "an empty series has no summary, only the empty label")

        val rising = (0 until 6).map { reading(noise = -100 + it * 2) }
        val summary = noiseFloorChartSummary(rising)!!
        assertEquals(6, summary.count)
        assertEquals(-100, summary.min)
        assertEquals(-90, summary.max)
        // Average -95.0 truncates to -95, matching the Int() conversion in the iOS chart label.
        assertEquals(-95, summary.average)
        assertEquals(NoiseFloorTrend.INCREASING, summary.trend)
    }

    private fun reading(noise: Int): NoiseFloorReading = NoiseFloorReading(
        id = Uuid.normalized("E0000000-0000-0000-0000-00000000000${kotlin.math.abs(noise) % 10}"),
        timestampEpochMs = 0,
        noiseFloor = noise,
        lastRssi = -50,
        lastSnr = 1.0,
    )

    @Test
    fun pollClassifiesReadingDisconnectAndFailure() = runTest {
        val reading = NoiseFloorReading(
            id = Uuid.normalized("A0000000-0000-0000-0000-000000000009"),
            timestampEpochMs = 0,
            noiseFloor = -100,
            lastRssi = -90,
            lastSnr = 5.0,
        )
        assertIs<NoiseFloorPoll.Sample>(pollNoiseFloor { reading })
        assertEquals(NoiseFloorPoll.Disconnected, pollNoiseFloor { null })
        assertEquals(
            NoiseFloorPoll.ReadFailed,
            pollNoiseFloor { error("radio stats request failed") },
            "a failed read is reported, not swallowed into a frozen chart",
        )
    }

    @Test
    fun pollPropagatesCancellation() = runTest {
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            pollNoiseFloor { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun pollPropagatesError() = runTest {
        val failure = NoiseFloorProbeError()
        val thrown = assertFailsWith<NoiseFloorProbeError> {
            pollNoiseFloor { throw failure }
        }
        assertSame(failure, thrown)
    }

    @Test
    fun pollOutcomeDrivesTheSeriesAndTheBanner() {
        val reading = NoiseFloorReading(
            id = Uuid.normalized("A0000000-0000-0000-0000-000000000009"),
            timestampEpochMs = 0,
            noiseFloor = -100,
            lastRssi = -90,
            lastSnr = 5.0,
        )
        val seeded = applyNoiseFloorPoll(NoiseFloorPoll.Sample(reading), emptyList(), "off", "unreadable")
        assertEquals(listOf(reading), seeded.readings)
        assertNull(seeded.errorMessage, "a good sample clears the banner")

        val failed = applyNoiseFloorPoll(NoiseFloorPoll.ReadFailed, seeded.readings, "off", "unreadable")
        assertEquals("unreadable", failed.errorMessage)
        assertEquals(seeded.readings, failed.readings, "a failed read leaves the series untouched")

        assertEquals("off", applyNoiseFloorPoll(NoiseFloorPoll.Disconnected, seeded.readings, "off", "x").errorMessage)
    }
}

private class NoiseFloorProbeError : Error("probe")

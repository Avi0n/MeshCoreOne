package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.chat.ui.TimestampFormatter
import one.meshcore.shared.chat.ui.chartLocalEpochDay
import one.meshcore.shared.chat.ui.chatMonthDayLabel
import one.meshcore.shared.remotenode.TelemetryChartPoint
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.timestampTestFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Time, not sample index, is the telemetry chart's X axis. */
class MetricChartScrubTest {
    @Test
    fun unevenTimestampsKeepTheGap() {
        val points = listOf(sample(0.0), sample(25.0), sample(100.0))
        assertEquals(listOf(0.0, 0.25, 1.0), metricChartXFractions(points))
    }

    @Test
    fun identicalTimestampsAreNotStretched() {
        val points = listOf(sample(5.0), sample(5.0), sample(5.0))
        assertEquals(listOf(0.0, 0.0, 0.0), metricChartXFractions(points))
    }

    @Test
    fun evenTimestampsMatchIndexSpacing() {
        val points = listOf(sample(0.0), sample(50.0), sample(100.0))
        assertEquals(listOf(0.0, 0.5, 1.0), metricChartXFractions(points))
    }

    @Test
    fun aLaterSeriesStaysInsideTheSharedWindow() {
        assertEquals(0.4, metricChartXFraction(40.0, start = 0.0, end = 100.0))
        assertEquals(0.6, metricChartXFraction(60.0, start = 0.0, end = 100.0))
    }

    @Test
    fun aLargeEpochKeepsAQuarterGap() {
        val start = 1_700_000_000.0
        val points = listOf(sample(start), sample(start + 25.0), sample(start + 100.0))
        assertEquals(listOf(0.0, 0.25, 1.0), metricChartXFractions(points))
    }

    @Test
    fun aSparseGapStaysNearTheEarlierSample() {
        val points = listOf(sample(0.0), sample(1.0), sample(1_000_000.0))
        assertEquals(1.0e-6, metricChartXFractions(points)[1], absoluteTolerance = 1e-12)
    }

    @Test
    fun anEmptySeriesHasNoFractions() {
        assertEquals(emptyList(), metricChartXFractions(emptyList()))
    }

    @Test
    fun denseEvenSamplesMatchIndexSpacing() {
        val points = (0 until 200).map { index -> sample(index.toDouble(), index.toDouble()) }
        val fractions = metricChartXFractions(points)
        assertEquals(0.0, fractions.first())
        assertEquals(1.0, fractions.last())
        assertEquals(100.0 / 199.0, fractions[100], absoluteTolerance = 1e-12)
    }

    @Test
    fun valuesOutsideTheDomainStayOutsideThePlot() {
        assertEquals(2.0, metricChartYFraction(10.0, domainMin = 0.0, domainMax = 5.0))
        assertEquals(-1.0, metricChartYFraction(-5.0, domainMin = 0.0, domainMax = 5.0))
        assertEquals(0.5, metricChartYFraction(4.0, domainMin = 4.0, domainMax = 4.0))
        assertEquals(0.0, metricChartYFraction(-130.0, domainMin = -130.0, domainMax = -60.0))
        assertEquals(1.0, metricChartYFraction(-60.0, domainMin = -130.0, domainMax = -60.0))
    }

    @Test
    fun scaleUsesTheSharedWindowAndACallerDomain() {
        val scale = metricChartScale(
            primary = listOf(sample(0.0, 1.0), sample(100.0, 2.0)),
            secondary = listOf(sample(40.0, 9.0)),
            valueDomain = 0.0..10.0,
        )
        assertEquals(0.0, scale?.timeStart)
        assertEquals(100.0, scale?.timeEnd)
        assertEquals(0.0, scale?.valueMin)
        assertEquals(10.0, scale?.valueMax)
    }

    @Test
    fun scaleAutoscaleKeepsExtremaAndIsNullWhenEmpty() {
        val scale = metricChartScale(
            primary = listOf(sample(0.0, -130.0), sample(10.0, -60.0)),
            secondary = emptyList(),
            valueDomain = null,
        )
        assertEquals(-130.0, scale?.valueMin)
        assertEquals(-60.0, scale?.valueMax)
        assertNull(metricChartScale(emptyList(), emptyList(), valueDomain = null))
    }

    @Test
    fun scrubSnapsToTheNearestPlottedTime() {
        val primary = listOf(sample(0.0, 1.0), sample(100.0, 2.0))
        val scrub = metricChartScrub(touchFraction = 0.6, primary = primary, secondary = emptyList())
        assertEquals(100.0, scrub?.snappedTimestamp)
        assertEquals(1.0, scrub?.xFraction)
        assertEquals(2.0, scrub?.primary?.value)
        assertNull(scrub?.secondary)
    }

    @Test
    fun scrubSharesOneTimeAcrossSeries() {
        val primary = listOf(sample(0.0, 1.0), sample(100.0, 2.0))
        val secondary = listOf(sample(10.0, 9.0))
        val scrub = metricChartScrub(touchFraction = 0.1, primary = primary, secondary = secondary)
        assertEquals(10.0, scrub?.snappedTimestamp)
        assertEquals(0.1, scrub?.xFraction)
        assertEquals(1.0, scrub?.primary?.value)
        assertEquals(9.0, scrub?.secondary?.value)
    }

    @Test
    fun dateTicksSpanTheTimeWindow() {
        assertEquals(listOf(0.0, 1.0 / 3.0, 2.0 / 3.0, 1.0), metricChartXTicks(0.0, 90.0))
        assertEquals(listOf(0.0, 30.0, 60.0, 90.0), metricChartXTickInstants(0.0, 90.0))
    }

    @Test
    fun aZeroSpanKeepsOneTickAtTheStart() {
        assertEquals(listOf(0.0), metricChartXTicks(40.0, 40.0))
        assertEquals(listOf(40.0), metricChartXTickInstants(40.0, 40.0))
    }

    @Test
    fun yTicksIncludeTheDomainExtrema() {
        assertEquals(listOf(-130.0, -95.0, -60.0), metricChartYTicks(-130.0, -60.0))
        assertEquals(listOf(4.0), metricChartYTicks(4.0, 4.0))
    }

    @Test
    fun localEpochDayChangesOnlyWhenTheMonthDayLabelDoes() {
        val morning = 1_700_000_000L
        val anHourLater = morning + 3_600L
        val muchLater = morning + 36L * 3_600L
        if (
            chatMonthDayLabel(morning, timestampTestFormatter) ==
            chatMonthDayLabel(anHourLater, timestampTestFormatter)
        ) {
            assertEquals(
                chartLocalEpochDay(morning, timestampTestFormatter),
                chartLocalEpochDay(anHourLater, timestampTestFormatter),
            )
        }
        assertNotEquals(
            chatMonthDayLabel(morning, timestampTestFormatter),
            chatMonthDayLabel(muchLater, timestampTestFormatter),
        )
        assertNotEquals(
            chartLocalEpochDay(morning, timestampTestFormatter),
            chartLocalEpochDay(muchLater, timestampTestFormatter),
        )
    }

    @Test
    fun axisLabelsReuseTheSameInstanceWhileTheTextStaysPut() {
        val start = 1_700_000_000.0
        val model = ChartDrawModel()
        val first = listOf(sample(start, 1.0), sample(start + 3_600.0, 2.0))
        val once = model.labelsFor(requireScale(first), timestampTestFormatter)
        val twice = model.labelsFor(requireScale(first), timestampTestFormatter)
        assertSame(once, twice)
        val grown = first + sample(start + 7_200.0, 1.5)
        val extended = model.labelsFor(requireScale(grown), timestampTestFormatter)
        assertEquals(chartAxisLabels(requireScale(grown), timestampTestFormatter), extended)
        if (
            chartAxisLabels(requireScale(first), timestampTestFormatter) ==
            chartAxisLabels(requireScale(grown), timestampTestFormatter)
        ) {
            assertSame(once, extended)
        }
    }

    @Test
    fun publishReusesAxisLabelsWhileTheTextStaysPut() {
        val start = 1_700_000_000.0
        val model = ChartDrawModel()
        val first = listOf(sample(start, 1.0), sample(start + 3_600.0, 2.0))
        model.publish(first, emptyList(), "dBm", null, timestampTestFormatter)
        val labels = model.axisLabels
        assertEquals(chartAxisLabels(requireScale(first), timestampTestFormatter), labels)
        assertEquals(first, model.primary)
        val grown = first + sample(start + 7_200.0, 1.5)
        model.publish(grown, emptyList(), "dBm", null, timestampTestFormatter)
        assertEquals(grown, model.primary)
        val grownLabels = chartAxisLabels(requireScale(grown), timestampTestFormatter)
        assertEquals(grownLabels, model.axisLabels)
        if (chartAxisLabels(requireScale(first), timestampTestFormatter) == grownLabels) {
            assertSame(labels, model.axisLabels)
        } else {
            assertNotSame(labels, model.axisLabels)
        }
    }

    @Test
    fun formatterReplacementUpdatesLabelsWithoutChangingTheModelDataOrDomain() {
        val firstFormatter = object : TimestampFormatter by timestampTestFormatter {
            override val unavailable = "First unavailable"
            override fun monthDay(epochSeconds: Long): String = "First date"
        }
        val secondFormatter = object : TimestampFormatter by timestampTestFormatter {
            override val unavailable = "Second unavailable"
            override fun monthDay(epochSeconds: Long): String = "Second date"
        }
        val start = 1_700_000_000.0
        val primary = listOf(sample(start, 1.0), sample(start + 3_600.0, 2.0))
        val secondary = listOf(sample(start + 1_800.0, 3.0))
        val domain = 0.0..10.0
        val model = ChartDrawModel()
        assertEquals(
            firstFormatter.localEpochDay(start.toLong()),
            secondFormatter.localEpochDay(start.toLong()),
        )

        model.publish(primary, secondary, "dBm", domain, firstFormatter)
        val firstLabels = model.axisLabels
        assertEquals("First date", firstLabels?.x0)
        model.publish(primary, secondary, "dBm", domain, firstFormatter)
        assertSame(firstLabels, model.axisLabels)

        model.publish(primary, secondary, "dBm", domain, secondFormatter)
        val secondLabels = model.axisLabels
        assertEquals("Second date", secondLabels?.x0)
        assertNotSame(firstLabels, secondLabels)
        assertSame(primary, model.primary)
        assertSame(secondary, model.secondary)
        assertSame(domain, model.yDomain)
        assertEquals("dBm", model.unit)
        model.publish(primary, secondary, "dBm", domain, secondFormatter)
        assertSame(secondLabels, model.axisLabels)
    }

    @Test
    fun unsupportedDayKeysReuseLabelsAndReplaceTheLocalizedFallback() {
        val firstFormatter = object : TimestampFormatter by timestampTestFormatter {
            override val unavailable = "First unavailable"
            override fun monthDay(epochSeconds: Long): String? = null
            override fun localEpochDay(epochSeconds: Long): Long? = null
        }
        val secondFormatter = object : TimestampFormatter by timestampTestFormatter {
            override val unavailable = "Second unavailable"
            override fun monthDay(epochSeconds: Long): String? = null
            override fun localEpochDay(epochSeconds: Long): Long? = null
        }
        val primary = listOf(sample(1_700_000_000.0, 1.0), sample(1_700_003_600.0, 2.0))
        val model = ChartDrawModel()
        val scale = requireScale(primary)
        model.publish(primary, emptyList(), "dBm", null, firstFormatter)
        val firstLabels = model.axisLabels
        assertEquals("First unavailable", firstLabels?.x0)
        assertSame(firstLabels, model.labelsFor(scale, firstFormatter))

        model.publish(primary, emptyList(), "dBm", null, secondFormatter)
        val secondLabels = model.axisLabels
        assertEquals("Second unavailable", secondLabels?.x0)
        assertNotSame(firstLabels, secondLabels)
        assertSame(primary, model.primary)
        assertSame(secondLabels, model.labelsFor(scale, secondFormatter))
    }

    @Test
    fun scrubClampsATouchOutsideThePlot() {
        val primary = listOf(sample(0.0, 1.0), sample(10.0, 2.0))
        val pastEnd = metricChartScrub(touchFraction = 1.4, primary = primary, secondary = emptyList())
        val beforeStart = metricChartScrub(touchFraction = -0.2, primary = primary, secondary = emptyList())
        assertEquals(10.0, pastEnd?.snappedTimestamp)
        assertEquals(0.0, beforeStart?.snappedTimestamp)
        assertNull(metricChartScrub(0.5, emptyList(), emptyList()))
    }

    private fun sample(time: Double, value: Double = 0.0) = TelemetryChartPoint(testUuid(1), time, value)

    private fun requireScale(points: List<TelemetryChartPoint>): MetricChartScale =
        checkNotNull(metricChartScale(points, emptyList(), valueDomain = null))
}

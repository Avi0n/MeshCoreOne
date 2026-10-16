package one.meshcore.shared.remotenode.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** iOS `MetricChartView.hasEnoughData` needs two samples before a line is drawn. */
class MetricChartDataGateTest {
    @Test
    fun oneSampleIsNotEnoughToDraw() {
        assertFalse(metricChartHasEnoughData(primaryCount = 1, secondaryCount = 0))
        assertFalse(metricChartHasEnoughData(primaryCount = 0, secondaryCount = 1))
        assertFalse(metricChartHasEnoughData(primaryCount = 0, secondaryCount = 0))
    }

    @Test
    fun twoSamplesOnEitherSeriesAreEnough() {
        assertTrue(metricChartHasEnoughData(primaryCount = 2, secondaryCount = 0))
        assertTrue(metricChartHasEnoughData(primaryCount = 0, secondaryCount = 2))
        assertTrue(metricChartHasEnoughData(primaryCount = 1, secondaryCount = 2))
    }

    @Test
    fun emptyStateSumsTheFirstValueOfEachDrawnSeries() {
        assertNull(metricChartEmptyStateValue(primaryFirst = null, secondaryFirst = null))
        assertEquals(3.5, metricChartEmptyStateValue(primaryFirst = 3.5, secondaryFirst = null))
        assertEquals(5.0, metricChartEmptyStateValue(primaryFirst = 2.0, secondaryFirst = 3.0))
    }

    @Test
    fun aDrawableChartUsesTheSectionTitleForTalkBack() {
        assertEquals("Battery", metricChartAccessibilityLabel("Battery", hasEnoughData = true))
        assertNull(metricChartAccessibilityLabel("Battery", hasEnoughData = false))
    }
}

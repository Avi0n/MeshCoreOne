package one.meshcore.shared.tools.ui

import one.meshcore.shared.trace.RttTrend
import one.meshcore.shared.trace.rttComparison
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Percent chrome; Increased/Decreased is TalkBack-only. Trend arrows are Material Symbols. */
class TraceComparisonDeltaVisibleTest {
    @Test
    fun changeTextIsPercentWithoutTheTalkBackWord() {
        val slower = rttComparison(currentMs = 150, previousMs = 100)
        val visible = traceComparisonChangeVisibleText(slower.trend, slower.percentChange)
        assertEquals("50%", visible)
        assertFalse(visible.contains("Increased"))
        assertFalse(visible.contains("Decreased"))
        assertTrue(traceComparisonChangeIsIncrease(slower.trend))
    }

    @Test
    fun aFasterRunShowsThePercentDrop() {
        val faster = rttComparison(currentMs = 80, previousMs = 100)
        assertEquals("20%", traceComparisonChangeVisibleText(faster.trend, faster.percentChange))
        assertFalse(traceComparisonChangeIsIncrease(faster.trend))
    }

    @Test
    fun anUnchangedRunHasNoDeltaChrome() {
        assertEquals("", traceComparisonChangeVisibleText(RttTrend.UNCHANGED, 0.0))
    }
}

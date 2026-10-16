package one.meshcore.shared.tools.ui

import one.meshcore.shared.trace.RttTrend
import one.meshcore.shared.ui.SignalChromeRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** iOS `ComparisonRowView` tints an increase red and a decrease green. */
class TraceComparisonChangeTintTest {
    @Test
    fun increasedIsRed() {
        assertEquals(TraceComparisonChangeTint.RED, traceComparisonChangeTint(RttTrend.INCREASED))
    }

    @Test
    fun decreasedIsGreen() {
        assertEquals(TraceComparisonChangeTint.GREEN, traceComparisonChangeTint(RttTrend.DECREASED))
    }

    @Test
    fun unchangedHasNoTint() {
        assertNull(traceComparisonChangeTint(RttTrend.UNCHANGED))
    }

    @Test
    fun changeTintsUseErrorAndPrimary() {
        assertEquals(SignalChromeRole.ERROR, TraceComparisonChangeTint.RED.chromeRole())
        assertEquals(SignalChromeRole.PRIMARY, TraceComparisonChangeTint.GREEN.chromeRole())
    }

    @Test
    fun savedPathListSubtitlePutsTheSuccessPercentInTheVisibleFacts() {
        assertEquals(
            "3 runs, Last: 2h ago, 90%",
            savedPathListSubtitle(runsLabel = "3 runs", lastRun = "Last: 2h ago", successRate = 90),
        )
    }

    @Test
    fun savedPathListSubtitleKeepsThePercentWhenThereIsNoLastRun() {
        assertEquals(
            "1 run, 100%",
            savedPathListSubtitle(runsLabel = "1 run", lastRun = null, successRate = 100),
        )
    }
}

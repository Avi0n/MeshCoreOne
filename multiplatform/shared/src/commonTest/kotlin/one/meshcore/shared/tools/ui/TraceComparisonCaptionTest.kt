package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** iOS `ComparisonRowView` fills the date slot with an abbreviated calendar date, not a placeholder. */
class TraceComparisonCaptionTest {
    @Test
    fun comparisonCaptionUsesTheDateNotPrev() {
        val caption = traceComparisonCaption(
            template = "vs. %d ms on %s",
            roundTripMs = 123,
            dateLabel = "Sep 5, 2026",
        )
        assertEquals("vs. 123 ms on Sep 5, 2026", caption)
        assertFalse(caption.contains("prev"))
    }
}

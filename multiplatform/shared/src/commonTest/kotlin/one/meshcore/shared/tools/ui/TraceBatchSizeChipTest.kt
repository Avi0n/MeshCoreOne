package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `PathActionsSectionView` offers 3× and 5× capsules, not a 2/10 ladder. */
class TraceBatchSizeChipTest {
    @Test
    fun chipsAreThreeAndFiveTimes() {
        assertEquals(listOf(3, 5), TRACE_BATCH_SIZE_OPTIONS)
        assertEquals("3×", traceBatchSizeChipLabel(3))
        assertEquals("5×", traceBatchSizeChipLabel(5))
    }
}

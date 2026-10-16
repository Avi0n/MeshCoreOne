package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Scroll-to-bottom matches iOS `ScrollToBottomButton`: opacity plus 0.5→1 scale over a 0.2 s snappy.
 */
class ScrollToBottomAnimationTest {
    @Test
    fun hiddenScaleAndDurationMatchIos() {
        assertEquals(200, SCROLL_TO_BOTTOM_ANIM_MS)
        assertEquals(0.5f, SCROLL_TO_BOTTOM_HIDDEN_SCALE)
    }
}

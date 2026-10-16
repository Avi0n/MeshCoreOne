package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeActionRowTest {
    @Test
    fun fullTrailingSwipeOnlyRevealsButtons() {
        assertEquals(-160f, swipeRevealTarget(offset = -160f, leadingWidth = 80f, trailingWidth = 160f))
        assertEquals(-160f, swipeRevealTarget(offset = -85f, leadingWidth = 80f, trailingWidth = 160f))
        assertEquals(0f, swipeRevealTarget(offset = -79f, leadingWidth = 80f, trailingWidth = 160f))
    }

    @Test
    fun leadingSwipeRevealsFavoriteAndUnavailableSidesStayClosed() {
        assertEquals(80f, swipeRevealTarget(offset = 60f, leadingWidth = 80f, trailingWidth = 160f))
        assertEquals(0f, swipeRevealTarget(offset = 60f, leadingWidth = 0f, trailingWidth = 160f))
        assertEquals(0f, swipeRevealTarget(offset = -100f, leadingWidth = 80f, trailingWidth = 0f))
    }
}

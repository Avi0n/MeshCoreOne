package one.meshcore.shared.tools.ui

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TracePathJumpTest {
    @Test
    fun onScreenBoundsCountAsVisible() {
        assertTrue(
            isLaidOutInWindow(
                bounds = Rect(left = 10f, top = 100f, right = 200f, bottom = 160f),
                windowWidth = 400f,
                windowHeight = 800f,
            ),
        )
    }

    @Test
    fun clippedOffTheBottomIsNotVisible() {
        assertFalse(
            isLaidOutInWindow(
                bounds = Rect(left = 10f, top = 900f, right = 200f, bottom = 960f),
                windowWidth = 400f,
                windowHeight = 800f,
            ),
        )
    }

    @Test
    fun emptyBoundsAreNotVisible() {
        assertFalse(
            isLaidOutInWindow(
                bounds = Rect(left = 10f, top = 100f, right = 10f, bottom = 100f),
                windowWidth = 400f,
                windowHeight = 800f,
            ),
        )
    }
}

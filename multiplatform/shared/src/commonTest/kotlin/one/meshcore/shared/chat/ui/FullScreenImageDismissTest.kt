package one.meshcore.shared.chat.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drag-to-dismiss matches iOS `FullScreenImageViewer.onDragEnded`: only at 1x zoom, and only when
 * the drag has passed 150pt or the fling is faster than 1000pt/s.
 */
class FullScreenImageDismissTest {
    @Test
    fun dismissesWhenDraggedPastThresholdAtOneX() {
        assertTrue(dismiss(dragY = 150f, velocityY = 0f, scale = 1f))
        assertTrue(dismiss(dragY = -200f, velocityY = 0f, scale = 1f))
    }

    @Test
    fun dismissesOnFastFlingBelowTheOffsetThreshold() {
        assertTrue(dismiss(dragY = 40f, velocityY = 1000f, scale = 1f))
        assertTrue(dismiss(dragY = -10f, velocityY = -1200f, scale = 1f))
    }

    @Test
    fun keepsTheViewerWhenTheDragAndFlingAreShort() {
        assertFalse(dismiss(dragY = 149f, velocityY = 999f, scale = 1f))
        assertFalse(dismiss(dragY = 0f, velocityY = 0f, scale = 1f))
    }

    @Test
    fun neverDismissesWhileZoomed() {
        assertFalse(dismiss(dragY = 400f, velocityY = 2000f, scale = 2f))
    }

    @Test
    fun doubleTapTogglesBetweenOneXAndThreeX() {
        assertEquals(FULLSCREEN_DOUBLE_TAP_SCALE, nextDoubleTapScale(1f))
        assertEquals(FULLSCREEN_MIN_SCALE, nextDoubleTapScale(3f))
        assertEquals(FULLSCREEN_MIN_SCALE, nextDoubleTapScale(5f))
    }

    @Test
    fun doubleTapAtOneXZoomsToThreeXCenteredOnTap() {
        val result = applyFullscreenDoubleTap(
            scale = 1f,
            tapX = 100f,
            tapY = 50f,
            viewportCenterX = 200f,
            viewportCenterY = 400f,
        )
        assertEquals(FULLSCREEN_DOUBLE_TAP_SCALE, result.scale)
        assertEquals(300f, result.offsetX)
        assertEquals(1050f, result.offsetY)
    }

    @Test
    fun doubleTapWhileZoomedResetsToOneXAndClearsPan() {
        val result = applyFullscreenDoubleTap(
            scale = 3f,
            tapX = 100f,
            tapY = 50f,
            viewportCenterX = 200f,
            viewportCenterY = 400f,
        )
        assertEquals(FULLSCREEN_MIN_SCALE, result.scale)
        assertEquals(0f, result.offsetX)
        assertEquals(0f, result.offsetY)
    }

    @Test
    fun viewerScrimUsesThemeScrimNotHardcodedBlack() {
        val scrim = Color(0xFF000000)
        assertEquals(scrim.copy(alpha = 0.7f), fullscreenImageScrim(scrim, fade = 0.7f))
    }

    private fun dismiss(dragY: Float, velocityY: Float, scale: Float): Boolean = shouldDismissFullScreenImage(
        dragY = dragY,
        velocityY = velocityY,
        scale = scale,
        thresholdPx = 150f,
        velocityThresholdPxPerSec = 1000f,
    )
}

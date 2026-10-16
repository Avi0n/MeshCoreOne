package one.meshcore.shared.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** Image chrome stays white so a dark theme cannot paint it the same color as the scrim. */
class ImageOverlayChromeTest {
    @Test
    fun overlayContentStaysOpaqueWhite() {
        assertEquals(Color.White, IMAGE_OVERLAY_CONTENT_COLOR)
        assertEquals(1f, IMAGE_OVERLAY_CONTENT_COLOR.alpha)
    }
}

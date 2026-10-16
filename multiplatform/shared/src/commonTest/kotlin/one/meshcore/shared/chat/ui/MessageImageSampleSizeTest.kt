package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Locks the extra subsample step that keeps a decoded edge at or under the bubble cap. */
class MessageImageSampleSizeTest {
    @Test
    fun edgesAtOrUnderTheCapStayFullSize() {
        assertEquals(1, messageImageSampleSize(MESSAGE_IMAGE_MAX_PIXEL_SIZE, 4))
        assertEquals(1, messageImageSampleSize(8, 8))
        assertEquals(1, messageImageSampleSize(0, 2400))
        assertNull(displayScaleDown(8, 8))
    }

    @Test
    fun wideImageTakesTheStepThatFitsUnderTheCap() {
        val sample = messageImageSampleSize(WIDE_WIDTH, WIDE_HEIGHT)
        assertEquals(4, sample)
        assertEquals(600, WIDE_WIDTH / sample)
        val scaled = displayScaleDown(WIDE_WIDTH, WIDE_HEIGHT)
        assertEquals(MESSAGE_IMAGE_MAX_PIXEL_SIZE, scaled?.width)
        assertEquals(1, scaled?.height)
    }

    private companion object {
        const val WIDE_WIDTH = 2400
        const val WIDE_HEIGHT = 4
    }
}

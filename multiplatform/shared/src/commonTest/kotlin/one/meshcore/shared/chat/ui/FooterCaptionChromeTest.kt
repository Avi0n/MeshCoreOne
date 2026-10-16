package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FooterCaptionChromeTest {
    @Test
    fun onlyAmbiguousRegionCaptionsAreInteractive() {
        assertFalse(footerCaptionIsInteractive(0))
        assertFalse(footerCaptionIsInteractive(1))
        assertTrue(footerCaptionIsInteractive(2))
    }

    @Test
    fun captionIconStaysCompact() {
        assertEquals(12, FOOTER_CAPTION_ICON_DP)
    }

    @Test
    fun hopPathRegionCaptionsUseCapsuleOutline() {
        assertEquals(6, FOOTER_CHIP_HORIZONTAL_PADDING_DP)
        assertEquals(2, FOOTER_CHIP_VERTICAL_PADDING_DP)
        assertEquals(1, FOOTER_CHIP_STROKE_DP)
        assertEquals(0.35f, FOOTER_CHIP_STROKE_ALPHA)
    }
}

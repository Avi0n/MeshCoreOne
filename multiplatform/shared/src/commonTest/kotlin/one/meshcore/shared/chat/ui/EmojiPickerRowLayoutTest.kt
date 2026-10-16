package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiPickerRowLayoutTest {
    @Test
    fun buttonMetricsMatchMaterialTonalIconButton() {
        assertEquals(40, EMOJI_PICKER_BUTTON_DP)
        assertEquals(8, EMOJI_PICKER_SPACING_DP)
        assertEquals(16, EMOJI_PICKER_HORIZONTAL_PADDING_DP)
    }

    @Test
    fun contentWidthIncludesMoreControlAndGaps() {
        // 6 emojis + more: 7*40 + 6*8 + 32 padding = 360.
        assertEquals(360, emojiPickerRowContentWidthDp(6))
        assertEquals(32, emojiPickerRowContentWidthDp(-1))
    }
}

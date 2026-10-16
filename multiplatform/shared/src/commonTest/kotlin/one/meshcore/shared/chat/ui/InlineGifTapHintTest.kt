package one.meshcore.shared.chat.ui

import androidx.compose.ui.graphics.Color
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_inline_image_tap_hint
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `GIFContentView` uses the same tap hint as the static inline image. */
class InlineGifTapHintTest {
    @Test
    fun anInlineGifSpeaksTheFullscreenTapHint() {
        assertEquals(Res.string.chats_inline_image_tap_hint, inlineGifTapHintRes())
    }

    @Test
    fun pausedScrimUsesThemeScrimNotHardcodedBlack() {
        val scrim = Color(0xFF000000)
        assertEquals(scrim.copy(alpha = GIF_PAUSED_SCRIM_ALPHA), gifPausedScrim(scrim))
    }
}

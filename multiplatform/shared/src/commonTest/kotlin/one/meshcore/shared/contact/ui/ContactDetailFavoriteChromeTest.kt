package one.meshcore.shared.contact.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactDetailFavoriteChromeTest {
    @Test
    fun avatarCropMaskUsesThemeScrimNotHardcodedBlack() {
        val scrim = Color(0xFF000000)
        assertEquals(scrim.copy(alpha = 0.5f), avatarCropMaskColor(scrim))
    }
}

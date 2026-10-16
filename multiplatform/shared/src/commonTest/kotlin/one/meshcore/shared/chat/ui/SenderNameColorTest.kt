package one.meshcore.shared.chat.ui

import one.meshcore.shared.avatar.NeutralAvatarPalette
import one.meshcore.shared.contact.ui.toComposeColor
import one.meshcore.shared.theme.ThemeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Incoming channel sender names use the selected theme's identity gamut, matching iOS
 * `UnifiedMessageBubble.senderColor`. Material primary and the System-only palette are wrong.
 */
class SenderNameColorTest {
    @Test
    fun paidThemeSenderNameLeavesTheSystemPalette() {
        val theme = ThemeRegistry.byId("fern")
        val color = senderIdentityColor(name = "Alice", theme = theme, useDark = false, highContrast = false)
        val expected = theme.identityGamut.colorForName(
            name = "Alice",
            backgroundLuminances = theme.identitySurfaceLuminances(useDark = false),
            highContrast = false,
        ).toComposeColor()
        assertEquals(expected, color)
        assertNotEquals(NeutralAvatarPalette.identityColor("Alice", dark = false).toComposeColor(), color)
    }

    @Test
    fun appearanceLightKeepsLightSenderColorsWhenThePhoneIsDark() {
        val theme = ThemeRegistry.byId("default")
        val color = senderIdentityColor(name = "Alice", theme = theme, useDark = false, highContrast = false)
        val systemDark = NeutralAvatarPalette.identityColor("Alice", dark = true).toComposeColor()
        assertNotEquals(systemDark, color)
    }
}

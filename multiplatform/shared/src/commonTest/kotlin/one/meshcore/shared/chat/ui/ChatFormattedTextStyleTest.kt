package one.meshcore.shared.chat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import one.meshcore.shared.avatar.NeutralAvatarPalette
import one.meshcore.shared.chat.text.LinkKind
import one.meshcore.shared.chat.text.TextSpan
import one.meshcore.shared.contact.ui.toComposeColor
import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.ThemeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Incoming hashtags and mentions take the selected Appearance theme, not Material primary or the
 * System gamut. Appearance Light must keep solving against light surfaces even when the phone is dark.
 */
class ChatFormattedTextStyleTest {
    private val baseColor = Color(0xFF111111)
    private val mention = TextSpan(
        start = 0,
        endExclusive = 6,
        kind = LinkKind.MENTION,
        url = "meshcore://mention/Alice",
        mentionName = "Alice",
    )
    private val hashtag = TextSpan(
        start = 0,
        endExclusive = 8,
        kind = LinkKind.HASHTAG,
        url = "meshcore://hashtag/general",
    )

    @Test
    fun incomingHashtagUsesTheThemeTokenNotPrimary() {
        val theme = ThemeRegistry.byId(AppTheme.DEFAULT_ID)
        val style = styledChatSpan(hashtag, palette(theme, outgoing = false))
        assertEquals(Color(theme.hashtagArgb(useDark = false)), style.color)
        assertEquals(FontWeight.Bold, style.fontWeight)
        assertNotEquals(Color(theme.accentArgb), style.color)
    }

    @Test
    fun incomingMentionUsesTheThemeGamutNotTheSystemPalette() {
        val theme = ThemeRegistry.byId("fern")
        val style = styledChatSpan(mention, palette(theme, outgoing = false))
        val expected = theme.identityGamut.colorForName(
            name = "Alice",
            backgroundLuminances = theme.identitySurfaceLuminances(useDark = false),
            highContrast = false,
        ).toComposeColor()
        assertEquals(expected, style.color)
        assertNotEquals(NeutralAvatarPalette.identityColor("Alice", dark = false).toComposeColor(), style.color)
    }

    @Test
    fun appearanceLightKeepsLightMentionColorsWhenThePhoneIsDark() {
        val theme = ThemeRegistry.byId(AppTheme.DEFAULT_ID)
        val style = styledChatSpan(mention, palette(theme, outgoing = false))
        val light = theme.identityGamut.colorForName(
            name = "Alice",
            backgroundLuminances = theme.identitySurfaceLuminances(useDark = false),
            highContrast = false,
        ).toComposeColor()
        val systemDark = NeutralAvatarPalette.identityColor("Alice", dark = true).toComposeColor()
        assertEquals(light, style.color)
        assertNotEquals(systemDark, style.color)
    }

    @Test
    fun outgoingHashtagAndMentionStayOnTheBubbleTextColor() {
        val theme = ThemeRegistry.byId("fern")
        val hashtagStyle = styledChatSpan(hashtag, palette(theme, outgoing = true))
        val mentionStyle = styledChatSpan(mention, palette(theme, outgoing = true))
        assertEquals(baseColor, hashtagStyle.color)
        assertEquals(baseColor, mentionStyle.color)
    }

    private fun palette(theme: AppTheme, outgoing: Boolean): ChatSpanPalette = ChatSpanPalette(
        outgoing = outgoing,
        theme = theme,
        useDark = false,
        highContrast = false,
        baseColor = baseColor,
    )
}

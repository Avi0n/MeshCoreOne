package one.meshcore.shared.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Incoming hashtag tokens come from the iOS theme Hashtag colorsets, not Material primary.
 * Light/dark (and high-contrast when the asset has one) must stay pinned to those fractions.
 */
class ThemeHashtagTest {
    @Test
    fun defaultHashtagIsTheTealTokenNotTheAccent() {
        val theme = ThemeRegistry.byId(AppTheme.DEFAULT_ID)
        assertEquals(HASHTAG_DEFAULT_LIGHT, theme.hashtagArgb(useDark = false))
        assertEquals(HASHTAG_DEFAULT_DARK, theme.hashtagArgb(useDark = true))
        assertNotEquals(theme.accentArgb, theme.hashtagArgb(useDark = false))
    }

    @Test
    fun defaultHighContrastUsesTheDarkerLightToken() {
        val theme = ThemeRegistry.byId(AppTheme.DEFAULT_ID)
        assertEquals(HASHTAG_DEFAULT_HIGH_CONTRAST_LIGHT, theme.hashtagArgb(useDark = false, highContrast = true))
        assertEquals(HASHTAG_DEFAULT_DARK, theme.hashtagArgb(useDark = true, highContrast = true))
    }

    @Test
    fun fernHashtagMatchesTheIosAssetInBothAppearances() {
        val theme = ThemeRegistry.byId("fern")
        assertEquals(HASHTAG_FERN_LIGHT, theme.hashtagArgb(useDark = false))
        assertEquals(HASHTAG_FERN_DARK, theme.hashtagArgb(useDark = true))
        assertEquals(HASHTAG_FERN_HIGH_CONTRAST_LIGHT, theme.hashtagArgb(useDark = false, highContrast = true))
    }

    @Test
    fun emberHashtagIgnoresSchemeBecauseTheThemeForcesDark() {
        val theme = ThemeRegistry.byId("ember")
        assertEquals(HASHTAG_EMBER, theme.hashtagArgb(useDark = false))
        assertEquals(HASHTAG_EMBER, theme.hashtagArgb(useDark = true))
        assertEquals(HASHTAG_EMBER_HIGH_CONTRAST, theme.hashtagArgb(useDark = false, highContrast = true))
    }

    @Test
    fun catppuccinDarkHighContrastUsesTheCombinedAsset() {
        val theme = ThemeRegistry.byId("catppuccin")
        assertEquals(HASHTAG_CATPPUCCIN_LIGHT, theme.hashtagArgb(useDark = false))
        assertEquals(HASHTAG_CATPPUCCIN_DARK, theme.hashtagArgb(useDark = true))
        assertEquals(HASHTAG_CATPPUCCIN_HIGH_CONTRAST_DARK, theme.hashtagArgb(useDark = true, highContrast = true))
    }
}

// iOS HashtagDefault + Theme/*/Hashtag sRGB fractions, rounded the same way as [RgbColor.argb].
private const val HASHTAG_DEFAULT_LIGHT = 0xFF007A8F
private const val HASHTAG_DEFAULT_DARK = 0xFF5AD2EA
private const val HASHTAG_DEFAULT_HIGH_CONTRAST_LIGHT = 0xFF006478
private const val HASHTAG_FERN_LIGHT = 0xFF2E6B1F
private const val HASHTAG_FERN_DARK = 0xFF9ED973
private const val HASHTAG_FERN_HIGH_CONTRAST_LIGHT = 0xFF245C17
private const val HASHTAG_EMBER = 0xFFFFBF4D
private const val HASHTAG_EMBER_HIGH_CONTRAST = 0xFFFFD166
private const val HASHTAG_CATPPUCCIN_LIGHT = 0xFF174FBE
private const val HASHTAG_CATPPUCCIN_DARK = 0xFF89B4FA
private const val HASHTAG_CATPPUCCIN_HIGH_CONTRAST_DARK = 0xFFA6C8FF

package one.meshcore.shared.settings.ui

import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.ThemeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppearanceThemeSwatchLabelTest {
    @Test
    fun emberIsDarkOnly() {
        val ember = ThemeRegistry.all.first { it.id == "ember" }
        assertFalse(ember.isDualMode)
        assertEquals(
            "Ember, dark only",
            themeSwatchSpokenName(
                themeName = "Ember",
                isDualMode = ember.isDualMode,
                dualTemplate = "%s, light and dark variants",
                darkOnlyTemplate = "%s, dark only",
            ),
        )
    }

    @Test
    fun defaultThemeSwatchUsesMaterialBaselinePrimaryNotAppleSystemBlue() {
        val default = ThemeRegistry.all.first { it.id == AppTheme.DEFAULT_ID }
        assertEquals(0xFF6750A4, default.accentArgb)
    }

    @Test
    fun defaultThemeIsDualMode() {
        val default = ThemeRegistry.all.first { it.id == AppTheme.DEFAULT_ID }
        assertTrue(default.isDualMode)
        assertEquals(
            "Default, light and dark variants",
            themeSwatchSpokenName(
                themeName = "Default",
                isDualMode = default.isDualMode,
                dualTemplate = "%s, light and dark variants",
                darkOnlyTemplate = "%s, dark only",
            ),
        )
    }

    @Test
    fun defaultThemeCaptionNamesWallpaperColorsOnlyWhenThePlatformUsesThem() {
        assertEquals(
            "Uses wallpaper colors",
            defaultThemeWallpaperCaption(
                themeId = AppTheme.DEFAULT_ID,
                usesWallpaper = true,
                caption = "Uses wallpaper colors",
            ),
        )
        assertEquals(
            null,
            defaultThemeWallpaperCaption(
                themeId = AppTheme.DEFAULT_ID,
                usesWallpaper = false,
                caption = "Uses wallpaper colors",
            ),
        )
        assertEquals(
            null,
            defaultThemeWallpaperCaption(
                themeId = "ember",
                usesWallpaper = true,
                caption = "Uses wallpaper colors",
            ),
        )
    }
}

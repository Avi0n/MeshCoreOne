package one.meshcore.shared.settings.ui

import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.ThemeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemeSwatchArgbTest {
    @Test
    fun defaultThemeHasNoPaletteSoTheSwatchFallsBack() {
        val roles = ThemeRegistry.byId(AppTheme.DEFAULT_ID).themeSwatchArgb()
        assertEquals(0xFF6750A4, roles.primary)
        assertNull(roles.secondary)
        assertNull(roles.canvas)
    }

    @Test
    fun darkOnlyThemeUsesTheDarkPalette() {
        val ember = ThemeRegistry.byId("ember")
        val roles = ember.themeSwatchArgb()
        assertEquals(ember.dark?.secondary, roles.secondary)
        assertEquals(ember.dark?.background, roles.canvas)
    }

    @Test
    fun dualModeThemePrefersLightRoles() {
        val fern = ThemeRegistry.byId("fern")
        val roles = fern.themeSwatchArgb()
        assertEquals(fern.light?.secondary, roles.secondary)
        assertEquals(fern.light?.background, roles.canvas)
    }
}

package one.meshcore.shared.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemeRegistryTest {
    @Test
    fun allNineNamedThemesPlusDefaultAreRegistered() {
        val ids = ThemeRegistry.all.map { it.id }
        assertEquals(
            listOf(
                "default",
                "ember",
                "fern",
                "marine",
                "olive",
                "lavender",
                "sakura",
                "solarized",
                "nord",
                "catppuccin",
            ),
            ids,
        )
        assertEquals(10, ThemeRegistry.all.size)
    }

    @Test
    fun byIdFallsBackToDefault() {
        assertEquals(AppTheme.DEFAULT_ID, ThemeRegistry.byId("missing").id)
        assertEquals("nord", ThemeRegistry.byId("nord").id)
    }

    @Test
    fun onlyDefaultIsFreeUntilBillingWiresEntitlements() {
        assertTrue(ThemeRegistry.byId("default").isFree)
        assertTrue(ThemeRegistry.all.filter { it.id != "default" }.none { it.isFree })
    }

    @Test
    fun namedThemesExposeFullMaterialRoles() {
        val fern = ThemeRegistry.byId("fern").materialColorScheme(useDark = false)
        assertEquals(Color(0xFF2E662E), fern.primary)
        assertNotEquals(fern.background, fern.surfaceContainer)
        assertNotEquals(fern.primary, fern.secondary)
        val ember = ThemeRegistry.byId("ember")
        assertEquals(true, ember.forceDark)
        assertNotNull(ember.dark)
        val dark = ember.materialColorScheme(useDark = false)
        assertEquals(Color(0xFF000000), dark.background)
    }

    @Test
    fun identityGamutsAreNonEmpty() {
        ThemeRegistry.all.forEach { theme ->
            assertTrue(theme.identityGamut.hueAnchors.isNotEmpty(), theme.id)
            val luminances = theme.identitySurfaceLuminances(useDark = false)
            assertEquals(2, luminances.size, theme.id)
        }
    }
}

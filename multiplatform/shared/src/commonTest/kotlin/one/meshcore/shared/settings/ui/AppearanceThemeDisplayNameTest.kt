package one.meshcore.shared.settings.ui

import one.meshcore.shared.theme.AppTheme
import one.meshcore.shared.theme.ThemeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppearanceThemeDisplayNameTest {
    @Test
    fun localizedThemesMatchIosSupportKeys() {
        assertEquals("Default", name(AppTheme.DEFAULT_ID))
        assertEquals("Ember", name("ember"))
        assertEquals("Fern", name("fern"))
        assertEquals("Marine", name("marine"))
        assertEquals("Olive", name("olive"))
        assertEquals("Lavender", name("lavender"))
    }

    @Test
    fun properNounThemesStayUntranslated() {
        assertEquals("Sakura", name("sakura"))
        assertEquals("Solarized", name("solarized"))
        assertEquals("Nord", name("nord"))
        assertEquals("Catppuccin", name("catppuccin"))
    }

    @Test
    fun unknownIdFallsBackToRawId() {
        assertEquals("unregistered", name("unregistered"))
    }

    @Test
    fun registryIdsAllHaveADisplayName() {
        ThemeRegistry.all.forEach { theme ->
            val label = name(theme.id)
            assertFalse(label.isBlank(), "theme ${theme.id} needs a hub label")
        }
    }

    private fun name(id: String): String = appearanceThemeDisplayName(
        id = id,
        names = AppearanceThemeNames(
            default = "Default",
            ember = "Ember",
            fern = "Fern",
            marine = "Marine",
            olive = "Olive",
            lavender = "Lavender",
        ),
    )
}

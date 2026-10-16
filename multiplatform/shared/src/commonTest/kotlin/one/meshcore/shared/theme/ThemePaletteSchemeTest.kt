package one.meshcore.shared.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ThemePaletteSchemeTest {
    @Test
    fun schemeRolesComeFromThePalette() {
        val dark = buildColorScheme(SAMPLE, dark = true)
        val light = buildColorScheme(SAMPLE, dark = false)

        assertEquals(Color(SAMPLE.primary), dark.primary)
        assertEquals(Color(SAMPLE.primary), light.primary)
        assertEquals(Color(SAMPLE.background), dark.background)
        assertEquals(Color(SAMPLE.background), light.background)
        assertEquals(Color(SAMPLE.surfaceContainer!!), dark.surfaceContainer)
        assertEquals(Color(SAMPLE.surfaceContainer!!), light.surfaceContainer)
        assertEquals(Color(DARK_ERROR), dark.error)
        assertEquals(Color(LIGHT_ERROR), light.error)
        assertEquals(Color.White, light.surfaceContainerLowest)
        assertNotEquals(light.surfaceContainerLowest, dark.surfaceContainerLowest)
    }

    @Test
    fun missingSurfaceContainerStillPaintsTheBackgroundAsSurface() {
        val palette = SAMPLE.copy(surfaceContainer = null)
        val scheme = buildColorScheme(palette, dark = false)

        assertEquals(Color(palette.background), scheme.surface)
        assertEquals(Color(palette.background), scheme.background)
    }
}

private const val SAMPLE_PRIMARY = 0xFF6750A4
private const val SAMPLE_SECONDARY = 0xFF625B71
private const val SAMPLE_BACKGROUND = 0xFFFFFBFE
private const val SAMPLE_CONTAINER = 0xFFE7E0EC
private const val DARK_ERROR = 0xFFF2B8B5
private const val LIGHT_ERROR = 0xFFB3261E

private val SAMPLE = ThemePalette(
    primary = SAMPLE_PRIMARY,
    secondary = SAMPLE_SECONDARY,
    background = SAMPLE_BACKGROUND,
    surfaceContainer = SAMPLE_CONTAINER,
)

package one.meshcore.shared.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppAppearanceTest {
    @Test
    fun `system follows device while explicit appearance ignores it`() {
        for (systemDark in listOf(false, true)) {
            for (forceDark in listOf(null, false)) {
                assertEquals(systemDark, resolveAppIsDark(AppColorSchemePreference.SYSTEM, systemDark, forceDark))
                assertEquals(false, resolveAppIsDark(AppColorSchemePreference.LIGHT, systemDark, forceDark))
                assertEquals(true, resolveAppIsDark(AppColorSchemePreference.DARK, systemDark, forceDark))
            }
        }
    }

    @Test
    fun `dark theme overrides every chrome preference`() {
        for (preference in AppColorSchemePreference.entries) {
            assertTrue(resolveAppIsDark(preference, systemDark = false, forceDark = true))
        }
    }
}

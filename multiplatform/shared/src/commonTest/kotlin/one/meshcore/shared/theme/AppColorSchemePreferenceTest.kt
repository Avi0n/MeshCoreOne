package one.meshcore.shared.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class AppColorSchemePreferenceTest {
    @Test
    fun fromRawParsesPinnedValues() {
        assertEquals(AppColorSchemePreference.SYSTEM, AppColorSchemePreference.fromRaw("system"))
        assertEquals(AppColorSchemePreference.LIGHT, AppColorSchemePreference.fromRaw("light"))
        assertEquals(AppColorSchemePreference.DARK, AppColorSchemePreference.fromRaw("dark"))
    }

    @Test
    fun fromRawFallsBackForNullAndUnknown() {
        assertEquals(AppColorSchemePreference.SYSTEM, AppColorSchemePreference.fromRaw(null))
        assertEquals(AppColorSchemePreference.SYSTEM, AppColorSchemePreference.fromRaw("auto"))
    }
}

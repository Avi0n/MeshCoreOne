package one.meshcore.shared.settings.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_appearance_scheme_dark
import one.meshcore.shared.resources.settings_appearance_scheme_light
import one.meshcore.shared.resources.settings_appearance_scheme_system
import one.meshcore.shared.theme.AppColorSchemePreference
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorSchemeChoicesTest {
    @Test
    fun labelsFollowSystemThenLightThenDark() {
        assertEquals(
            listOf(
                AppColorSchemePreference.SYSTEM to Res.string.settings_appearance_scheme_system,
                AppColorSchemePreference.LIGHT to Res.string.settings_appearance_scheme_light,
                AppColorSchemePreference.DARK to Res.string.settings_appearance_scheme_dark,
            ),
            AppColorSchemePreference.entries.map { it to it.schemeLabelResource() },
        )
    }
}

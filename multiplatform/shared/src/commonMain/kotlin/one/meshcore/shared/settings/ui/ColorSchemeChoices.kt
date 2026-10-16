package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_appearance_scheme_dark
import one.meshcore.shared.resources.settings_appearance_scheme_light
import one.meshcore.shared.resources.settings_appearance_scheme_system
import one.meshcore.shared.theme.AppColorSchemePreference
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** System, light, then dark, matching [AppColorSchemePreference] declaration order. */
@Composable
internal fun colorSchemeChoices(): List<Pair<AppColorSchemePreference, String>> =
    AppColorSchemePreference.entries.map { preference ->
        preference to stringResource(preference.schemeLabelResource())
    }

internal fun AppColorSchemePreference.schemeLabelResource(): StringResource = when (this) {
    AppColorSchemePreference.SYSTEM -> Res.string.settings_appearance_scheme_system
    AppColorSchemePreference.LIGHT -> Res.string.settings_appearance_scheme_light
    AppColorSchemePreference.DARK -> Res.string.settings_appearance_scheme_dark
}

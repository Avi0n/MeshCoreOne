package one.meshcore.shared.theme

/** Resolves chrome appearance after the selected theme's light/dark override. */
fun resolveAppIsDark(preference: AppColorSchemePreference, systemDark: Boolean, forceDark: Boolean?): Boolean =
    if (forceDark == true) {
        true
    } else {
        when (preference) {
            AppColorSchemePreference.SYSTEM -> systemDark
            AppColorSchemePreference.LIGHT -> false
            AppColorSchemePreference.DARK -> true
        }
    }

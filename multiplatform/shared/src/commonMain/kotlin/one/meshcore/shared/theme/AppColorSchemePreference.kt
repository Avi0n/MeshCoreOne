package one.meshcore.shared.theme

/**
 * Global app appearance preference (independent of named theme palettes). Raw values are pinned to
 * the Swift `AppColorSchemePreference` strings so backups stay portable.
 */
enum class AppColorSchemePreference(val rawValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromRaw(raw: String?): AppColorSchemePreference {
            if (raw == null) return SYSTEM
            return entries.firstOrNull { it.rawValue == raw } ?: SYSTEM
        }
    }
}

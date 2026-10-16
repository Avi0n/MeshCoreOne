package one.meshcore.shared.map

import one.meshcore.shared.theme.AppColorSchemePreference

/**
 * Resolves whether map basemap tiles and pin sprites should use the dark style. Driven by the
 * Settings → Maps basemap-appearance preference: `system` follows the resolved app Appearance
 * (`LocalUseDarkTheme` / iOS `@Environment(\.colorScheme)`), `light`/`dark` force the basemap
 * regardless of chrome. Mirrors the iOS `resolvedMapIsDark`.
 */
fun resolveMapIsDark(preference: AppColorSchemePreference, systemDark: Boolean): Boolean = when (preference) {
    AppColorSchemePreference.SYSTEM -> systemDark
    AppColorSchemePreference.LIGHT -> false
    AppColorSchemePreference.DARK -> true
}

/**
 * Settings → Maps appearance for a host. A missing store (desktop / tests) uses `.system`, matching
 * the iOS `@AppStorage` default, so the basemap follows resolved app Appearance.
 */
fun mapsAppearancePreference(stored: AppColorSchemePreference?): AppColorSchemePreference =
    stored ?: AppColorSchemePreference.SYSTEM

/**
 * The base vector style a map host should load. Offline overrides appearance: packs are downloaded
 * against Liberty and MapLibre serves cached tiles only for the exact style URL used at download time,
 * so a dark basemap while offline renders as a blank map. Mirrors the iOS `MapContentView` call that
 * passes `isOffline` alongside the resolved dark flag.
 */
fun basemapStyleUrl(
    style: MapStyleSelection,
    preference: AppColorSchemePreference,
    systemDark: Boolean,
    isOffline: Boolean,
): String = style.styleUrl(isDarkMode = resolveMapIsDark(preference, systemDark), isOffline = isOffline)

/** The persisted Map-tab style, or Standard when the key is missing or unknown. */
fun mapStyleFromRaw(raw: String?): MapStyleSelection =
    MapStyleSelection.fromRaw(raw.orEmpty()) ?: MapStyleSelection.STANDARD

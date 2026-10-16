package one.meshcore.shared.map

import one.meshcore.shared.theme.AppColorSchemePreference
import kotlin.test.Test
import kotlin.test.assertEquals

/** The basemap dark-style decision: `system` follows resolved app Appearance, `light`/`dark` force it. */
class MapAppearanceTest {
    @Test
    fun systemFollowsResolvedChrome() {
        // Appearance Light while the phone is Dark must stay light, matching iOS Environment colorScheme.
        assertEquals(true, resolveMapIsDark(AppColorSchemePreference.SYSTEM, systemDark = true))
        assertEquals(false, resolveMapIsDark(AppColorSchemePreference.SYSTEM, systemDark = false))
    }

    @Test
    fun lightForcesLightRegardlessOfTheme() {
        assertEquals(false, resolveMapIsDark(AppColorSchemePreference.LIGHT, systemDark = true))
        assertEquals(false, resolveMapIsDark(AppColorSchemePreference.LIGHT, systemDark = false))
    }

    @Test
    fun darkForcesDarkRegardlessOfTheme() {
        assertEquals(true, resolveMapIsDark(AppColorSchemePreference.DARK, systemDark = false))
        assertEquals(true, resolveMapIsDark(AppColorSchemePreference.DARK, systemDark = true))
    }

    @Test
    fun missingStoreUsesSystemAppearance() {
        assertEquals(AppColorSchemePreference.SYSTEM, mapsAppearancePreference(null))
    }

    @Test
    fun storedMapsAppearanceIsHonored() {
        assertEquals(AppColorSchemePreference.LIGHT, mapsAppearancePreference(AppColorSchemePreference.LIGHT))
        assertEquals(AppColorSchemePreference.DARK, mapsAppearancePreference(AppColorSchemePreference.DARK))
    }

    @Test
    fun onlineDarkBasemapUsesTheDarkStyle() {
        assertEquals(
            MapTileUrls.OPEN_FREE_MAP_DARK,
            basemapStyleUrl(
                style = MapStyleSelection.STANDARD,
                preference = AppColorSchemePreference.DARK,
                systemDark = false,
                isOffline = false,
            ),
        )
    }

    @Test
    fun offlinePinsTheBasemapToLibertyEvenWhenDarkIsForced() {
        // Packs are downloaded against Liberty; requesting dark offline renders a blank map.
        assertEquals(
            MapTileUrls.OPEN_FREE_MAP_LIBERTY,
            basemapStyleUrl(
                style = MapStyleSelection.TOPO,
                preference = AppColorSchemePreference.DARK,
                systemDark = true,
                isOffline = true,
            ),
        )
    }
}

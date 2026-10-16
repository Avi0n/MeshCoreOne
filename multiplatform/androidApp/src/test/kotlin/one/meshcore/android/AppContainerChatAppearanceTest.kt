package one.meshcore.android

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.MapSnapshotRequest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.ChatDisplayPreferences
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.theme.AppearancePreferencesStore
import one.meshcore.shared.theme.resolveAppIsDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContainerChatAppearanceTest {
    @Test
    fun prewarmingKeepsMapAppearanceSeparateFromChrome() = runTest {
        for (mapDark in listOf(false, true)) {
            val env = ChatDisplayPreferences(showMapPreviewThumbnails = true).buildChatPrimeEnvInputs(
                currentUserName = "Me",
                chromeIsDark = !mapDark,
                mapPreference = if (mapDark) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT,
                isOffline = false,
                string = { error("Path labels are disabled") },
            )
            assertEquals(!mapDark, env.isDark)
            assertEquals(mapDark, env.mapBasemapIsDark)
            assertTrue(env.showMapPreviews)
        }
    }

    @Test
    fun primeHookReadsLiveMapPreferencesAndResolvedChrome() = runTest {
        val store = InMemoryPreferencesStore()
        val map = MapDisplayPreferencesStore(store, backgroundScope)
        val appearance = AppearancePreferencesStore(store, backgroundScope)
        appearance.setColorScheme(AppColorSchemePreference.LIGHT)
        map.setMapColorScheme(AppColorSchemePreference.DARK)
        val produce: suspend () -> EnvInputs = {
            ChatDisplayPreferences().buildChatPrimeEnvInputs(
                currentUserName = "Me",
                chromeIsDark = resolveAppIsDark(appearance.colorScheme.value, systemDark = true, forceDark = null),
                mapPreference = map.mapColorScheme.value,
                isOffline = false,
                string = { error("Path labels are disabled") },
            )
        }
        val dark = produce()
        assertFalse(dark.isDark)
        assertTrue(dark.mapBasemapIsDark)
        map.setMapColorScheme(AppColorSchemePreference.SYSTEM)
        val light = produce()
        assertFalse(light.isDark)
        assertFalse(light.mapBasemapIsDark)
        assertNotEquals(
            MapSnapshotRequest.rounded(37.77, -122.42, dark.mapBasemapIsDark, dark.isOffline),
            MapSnapshotRequest.rounded(37.77, -122.42, light.mapBasemapIsDark, light.isOffline),
        )
        appearance.setColorScheme(AppColorSchemePreference.DARK)
        assertTrue(produce().mapBasemapIsDark)
    }

    @Test
    fun prewarmingPreservesOfflineAndLocalizedPathInputs() = runTest {
        val env = ChatDisplayPreferences(showIncomingPath = true, showIncomingRegion = true).buildChatPrimeEnvInputs(
            currentUserName = "Me",
            chromeIsDark = true,
            mapPreference = AppColorSchemePreference.LIGHT,
            isOffline = true,
            string = { it.key },
        )
        assertTrue(env.isDark)
        assertFalse(env.mapBasemapIsDark)
        assertTrue(env.isOffline)
        assertTrue(env.showIncomingRegion)
        assertEquals("chats_message_path_direct", env.incomingPathLabels?.direct)
        assertEquals("chats_message_path_flood", env.incomingPathLabels?.flood)
    }
}

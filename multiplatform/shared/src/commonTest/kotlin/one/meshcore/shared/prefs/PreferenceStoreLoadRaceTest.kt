package one.meshcore.shared.prefs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.settings.ChatDisplayPreferencesStore
import one.meshcore.shared.settings.DemoModeStore
import one.meshcore.shared.settings.TranslationTargetPreferencesStore
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.theme.AppearancePreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PreferenceStoreLoadRaceTest {
    @Test
    fun appearanceChangeWinsOverAnOlderLoad() = runTest {
        val prefs = GatedPreferencesStore(
            mapOf(
                PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE to "light",
                PersistenceKeys.SELECTED_THEME_ID to "ember",
            ),
            PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE,
        )
        val store = AppearancePreferencesStore(prefs, backgroundScope)

        store.load()
        runCurrent()
        prefs.readStarted.await()
        store.setColorScheme(AppColorSchemePreference.DARK)
        prefs.releaseRead.complete(Unit)
        runCurrent()

        assertEquals(AppColorSchemePreference.DARK, store.colorScheme.value)
        assertEquals("ember", store.selectedThemeId.value)
    }

    @Test
    fun mapChangeWinsOverAnOlderLoad() = runTest {
        val prefs = GatedPreferencesStore(
            mapOf(
                PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP to false,
                PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE to "dark",
            ),
            PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP,
        )
        val store = MapDisplayPreferencesStore(prefs, backgroundScope)

        store.load()
        runCurrent()
        prefs.readStarted.await()
        store.setShowDiscoveredNodesOnMap(true)
        prefs.releaseRead.complete(Unit)
        runCurrent()

        assertTrue(store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.DARK, store.mapColorScheme.value)
    }

    @Test
    fun translationChangeWinsOverAnOlderLoad() = runTest {
        val prefs = GatedPreferencesStore(
            mapOf(
                PersistenceKeys.TRANSLATION_TARGET_LANGUAGE to "de",
                PersistenceKeys.TRANSLATION_OFFERS_ENABLED to false,
            ),
            PersistenceKeys.TRANSLATION_TARGET_LANGUAGE,
        )
        val store = TranslationTargetPreferencesStore(prefs, backgroundScope)

        store.load()
        runCurrent()
        prefs.readStarted.await()
        store.setLanguageRawValue("fr")
        prefs.releaseRead.complete(Unit)
        runCurrent()

        assertEquals("fr", store.state.value.languageRawValue)
        assertFalse(store.state.value.translationOffersEnabled)
    }

    @Test
    fun demoUnlockWinsOverAnOlderLoad() = runTest {
        val prefs = GatedPreferencesStore(
            mapOf(
                PersistenceKeys.IS_DEMO_MODE_UNLOCKED to false,
                PersistenceKeys.IS_SCREENSHOT_MODE_ENABLED to true,
            ),
            PersistenceKeys.IS_DEMO_MODE_UNLOCKED,
        )
        val store = DemoModeStore(prefs, backgroundScope)

        store.load()
        runCurrent()
        prefs.readStarted.await()
        store.unlock()
        prefs.releaseRead.complete(Unit)
        runCurrent()

        assertTrue(store.isUnlocked.value)
        assertTrue(store.isEnabled.value)
        assertTrue(store.isScreenshotModeEnabled.value)
    }

    @Test
    fun delegatedChatPreviewChangeWinsOverAnOlderLoad() = runTest {
        val prefs = GatedPreferencesStore(
            mapOf(
                PersistenceKeys.AUTO_PLAY_GIFS to true,
                PersistenceKeys.SHOW_INCOMING_REGION to true,
            ),
            PersistenceKeys.AUTO_PLAY_GIFS,
        )
        val store = ChatDisplayPreferencesStore(prefs, backgroundScope)

        store.load()
        runCurrent()
        prefs.readStarted.await()
        store.setAutoPlayGIFs(false)
        prefs.releaseRead.complete(Unit)
        runCurrent()

        assertFalse(store.state.value.autoPlayGIFs)
        assertTrue(store.state.value.showIncomingRegion)
    }
}

private class GatedPreferencesStore(
    initial: Map<String, Any>,
    private val gatedKey: String,
    private val backing: InMemoryPreferencesStore = InMemoryPreferencesStore(initial),
) : PreferencesStore by backing {
    val readStarted = CompletableDeferred<Unit>()
    val releaseRead = CompletableDeferred<Unit>()

    override suspend fun getString(key: String): String? = gatedRead(key) { backing.getString(key) }

    override suspend fun getBoolean(key: String): Boolean? = gatedRead(key) { backing.getBoolean(key) }

    override suspend fun putString(key: String, value: String) = backing.putString(key, value)

    override suspend fun putBoolean(key: String, value: Boolean) = backing.putBoolean(key, value)

    private suspend fun <T> gatedRead(key: String, read: suspend () -> T): T {
        val value = read()
        if (key == gatedKey) {
            readStarted.complete(Unit)
            releaseRead.await()
        }
        return value
    }
}

package one.meshcore.shared.theme

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppearancePreferencesStoreTest {
    @Test
    fun keepingTheSystemAppearanceSchemeDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = AppearancePreferencesStore(prefs, this)
        assertEquals(AppColorSchemePreference.SYSTEM, store.colorScheme.value)
        assertEquals(PersistenceKeys.DEFAULT_THEME_ID, store.selectedThemeId.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setColorScheme(AppColorSchemePreference.SYSTEM)
        advanceUntilIdle()

        assertEquals(AppColorSchemePreference.SYSTEM, store.colorScheme.value)
        assertEquals(PersistenceKeys.DEFAULT_THEME_ID, store.selectedThemeId.value)
        assertEquals(
            AppColorSchemePreference.SYSTEM.rawValue,
            prefs.getString(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE),
        )
        assertNull(prefs.getString(PersistenceKeys.SELECTED_THEME_ID))
        assertNull(prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(
            mapOf(
                PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE to AppColorSchemePreference.SYSTEM.rawValue,
            ),
            prefs.snapshot(),
        )
    }

    @Test
    fun choosingAnAppearanceColorSchemeDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = AppearancePreferencesStore(prefs, this)
        assertEquals(AppColorSchemePreference.SYSTEM, store.colorScheme.value)

        store.setColorScheme(AppColorSchemePreference.DARK)
        assertEquals(AppColorSchemePreference.DARK, store.colorScheme.value)
        advanceUntilIdle()

        assertEquals("dark", prefs.getString(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE))
        assertNull(prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(
            mapOf(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE to "dark"),
            prefs.snapshot(),
        )
    }

    @Test
    fun keepingTheDefaultAppearanceThemeDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = AppearancePreferencesStore(prefs, this)
        assertEquals(PersistenceKeys.DEFAULT_THEME_ID, store.selectedThemeId.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.colorScheme.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setSelectedThemeId(PersistenceKeys.DEFAULT_THEME_ID)
        advanceUntilIdle()

        assertEquals(PersistenceKeys.DEFAULT_THEME_ID, store.selectedThemeId.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.colorScheme.value)
        assertEquals(
            PersistenceKeys.DEFAULT_THEME_ID,
            prefs.getString(PersistenceKeys.SELECTED_THEME_ID),
        )
        assertNull(prefs.getString(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE))
        assertNull(prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(
            mapOf(PersistenceKeys.SELECTED_THEME_ID to PersistenceKeys.DEFAULT_THEME_ID),
            prefs.snapshot(),
        )
    }

    @Test
    fun choosingAnAppearanceThemeDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = AppearancePreferencesStore(prefs, this)
        assertEquals(PersistenceKeys.DEFAULT_THEME_ID, store.selectedThemeId.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.colorScheme.value)

        store.setSelectedThemeId("ember")
        assertEquals("ember", store.selectedThemeId.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.colorScheme.value)
        advanceUntilIdle()

        assertEquals("ember", prefs.getString(PersistenceKeys.SELECTED_THEME_ID))
        assertNull(prefs.getString(PersistenceKeys.APP_COLOR_SCHEME_PREFERENCE))
        assertNull(prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(
            mapOf(PersistenceKeys.SELECTED_THEME_ID to "ember"),
            prefs.snapshot(),
        )
    }
}

package one.meshcore.shared.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.translation.TranslationTargetPreference
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationTargetPreferencesStoreTest {
    @Test
    fun defaultsWhenEmpty() = runTest {
        val prefs = InMemoryPreferencesStore().loadTranslationTargetPreferences()
        assertEquals(PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE, prefs.languageRawValue)
        assertFalse(prefs.useDefaultTranslationApp)
        assertFalse(prefs.usesSystemOverlay)
        assertTrue(prefs.translationOffersEnabled)
    }

    @Test
    fun offerToggleUpdatesImmediatelyAndSurvivesReload() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        store.setTranslationOffersEnabled(false)
        assertFalse(store.state.value.translationOffersEnabled)
        advanceUntilIdle()
        assertEquals(false, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))

        val reloaded = TranslationTargetPreferencesStore(prefs, this)
        reloaded.load()
        advanceUntilIdle()
        assertFalse(reloaded.state.value.translationOffersEnabled)
        reloaded.setTranslationOffersEnabled(true)
        advanceUntilIdle()
        assertEquals(true, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
    }

    @Test
    fun pickingALanguageClearsTheDefaultAppToggle() = runTest {
        val prefs = InMemoryPreferencesStore()
        prefs.putBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP, true)
        val store = TranslationTargetPreferencesStore(prefs, this)
        store.load()
        advanceUntilIdle()
        store.setLanguageRawValue("da")
        advanceUntilIdle()
        assertEquals("da", store.state.value.languageRawValue)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertEquals("da", prefs.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE))
        assertEquals(false, prefs.getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP))
    }

    @Test
    fun keepingTheDefaultTranslationAppOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.usesSystemOverlay)
        assertTrue(store.state.value.translationOffersEnabled)
        assertTrue(prefs.snapshot().isEmpty())

        store.setUseDefaultTranslationApp(false)
        advanceUntilIdle()

        assertFalse(store.state.value.useDefaultTranslationApp)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.usesSystemOverlay)
        assertTrue(store.state.value.translationOffersEnabled)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP))
        assertEquals(null, prefs.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
        assertEquals(
            mapOf(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP to false),
            prefs.snapshot(),
        )
    }

    @Test
    fun turningOffDefaultAppRewritesTheOverlaySentinelToApp() = runTest {
        val prefs = InMemoryPreferencesStore()
        prefs.putString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE, TranslationTargetPreference.SYSTEM_OVERLAY)
        val store = TranslationTargetPreferencesStore(prefs, this)
        store.load()
        advanceUntilIdle()
        assertTrue(store.state.value.usesSystemOverlay)
        store.setUseDefaultTranslationApp(false)
        advanceUntilIdle()
        assertEquals(TranslationTargetPreference.MATCH_APP_LANGUAGE, store.state.value.languageRawValue)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertEquals(
            TranslationTargetPreference.MATCH_APP_LANGUAGE,
            prefs.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE),
        )
    }

    @Test
    fun adoptingAnOverlaySentinelWithoutOneSetDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        assertEquals(PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE, store.state.value.languageRawValue)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertFalse(store.state.value.usesSystemOverlay)
        assertTrue(store.state.value.translationOffersEnabled)
        assertTrue(prefs.snapshot().isEmpty())

        store.adoptOverlaySentinel()
        advanceUntilIdle()

        assertEquals(PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE, store.state.value.languageRawValue)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertFalse(store.state.value.usesSystemOverlay)
        assertTrue(store.state.value.translationOffersEnabled)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun adoptOverlaySentinelTurnsDefaultAppOnAndStoresAppLanguage() = runTest {
        val prefs = InMemoryPreferencesStore()
        prefs.putString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE, TranslationTargetPreference.SYSTEM_OVERLAY)
        val store = TranslationTargetPreferencesStore(prefs, this)
        store.load()
        advanceUntilIdle()
        store.adoptOverlaySentinel()
        advanceUntilIdle()
        assertEquals(TranslationTargetPreference.MATCH_APP_LANGUAGE, store.state.value.languageRawValue)
        assertTrue(store.state.value.useDefaultTranslationApp)
        assertTrue(store.state.value.usesSystemOverlay)
    }

    @Test
    fun keepingTranslationOffersOnDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        assertTrue(store.state.value.translationOffersEnabled)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertFalse(store.state.value.usesSystemOverlay)
        assertTrue(prefs.snapshot().isEmpty())

        store.setTranslationOffersEnabled(true)
        advanceUntilIdle()

        assertTrue(store.state.value.translationOffersEnabled)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertFalse(store.state.value.usesSystemOverlay)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
        assertEquals(null, prefs.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP))
        assertEquals(
            mapOf(PersistenceKeys.TRANSLATION_OFFERS_ENABLED to true),
            prefs.snapshot(),
        )
    }

    @Test
    fun turningOffTranslationOffersDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        assertTrue(store.state.value.translationOffersEnabled)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.useDefaultTranslationApp)

        store.setTranslationOffersEnabled(false)

        assertFalse(store.state.value.translationOffersEnabled)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.useDefaultTranslationApp)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
        assertEquals(
            mapOf(PersistenceKeys.TRANSLATION_OFFERS_ENABLED to false),
            prefs.snapshot(),
        )
    }

    @Test
    fun keepingTheAppLanguageDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertFalse(store.state.value.usesSystemOverlay)
        assertTrue(store.state.value.translationOffersEnabled)
        assertTrue(prefs.snapshot().isEmpty())

        store.setLanguageRawValue(PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE)
        advanceUntilIdle()

        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertFalse(store.state.value.usesSystemOverlay)
        assertTrue(store.state.value.translationOffersEnabled)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            prefs.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE),
        )
        assertEquals(false, prefs.getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
        assertEquals(
            mapOf(
                PersistenceKeys.TRANSLATION_TARGET_LANGUAGE to
                    PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
                PersistenceKeys.USE_DEFAULT_TRANSLATION_APP to false,
            ),
            prefs.snapshot(),
        )
    }

    @Test
    fun choosingATranslationLanguageDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        assertEquals(PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE, store.state.value.languageRawValue)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertTrue(store.state.value.translationOffersEnabled)

        store.setLanguageRawValue("fr")
        assertEquals("fr", store.state.value.languageRawValue)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertTrue(store.state.value.translationOffersEnabled)
        advanceUntilIdle()

        assertEquals("fr", prefs.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE))
        assertEquals(false, prefs.getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
        assertEquals(
            mapOf(
                PersistenceKeys.TRANSLATION_TARGET_LANGUAGE to "fr",
                PersistenceKeys.USE_DEFAULT_TRANSLATION_APP to false,
            ),
            prefs.snapshot(),
        )
    }

    @Test
    fun turningOnTheDefaultTranslationAppDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = TranslationTargetPreferencesStore(prefs, this)
        assertFalse(store.state.value.useDefaultTranslationApp)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertTrue(store.state.value.translationOffersEnabled)

        store.setUseDefaultTranslationApp(true)

        assertTrue(store.state.value.useDefaultTranslationApp)
        assertEquals(
            PersistenceKeys.DEFAULT_TRANSLATION_TARGET_LANGUAGE,
            store.state.value.languageRawValue,
        )
        assertTrue(store.state.value.translationOffersEnabled)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP))
        assertEquals(null, prefs.getString(PersistenceKeys.TRANSLATION_TARGET_LANGUAGE))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.TRANSLATION_OFFERS_ENABLED))
        assertEquals(
            mapOf(PersistenceKeys.USE_DEFAULT_TRANSLATION_APP to true),
            prefs.snapshot(),
        )
    }
}

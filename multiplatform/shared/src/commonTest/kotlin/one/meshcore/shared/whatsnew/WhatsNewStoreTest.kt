package one.meshcore.shared.whatsnew

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsNewStoreTest {
    @Test
    fun dismissingWhatsNewDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = WhatsNewStore(prefs, this)
        assertNull(store.pendingRelease.value)

        store.evaluate(isOnboarded = true)
        advanceUntilIdle()

        assertEquals(WhatsNewCatalog.currentVersion, store.pendingRelease.value?.version)
        assertNull(prefs.getString(LAST_SHOWN_WHATS_NEW_VERSION))

        store.markShown()
        assertNull(store.pendingRelease.value)
        advanceUntilIdle()

        val shown = WhatsNewCatalog.currentVersion.encode()
        assertEquals(shown, prefs.getString(LAST_SHOWN_WHATS_NEW_VERSION))
        assertEquals(mapOf(LAST_SHOWN_WHATS_NEW_VERSION to shown), prefs.snapshot())
    }

    @Test
    fun aScreenshotModeWhatsNewCheckDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = WhatsNewStore(prefs, this)

        store.evaluate(isOnboarded = true, isScreenshotMode = true)
        advanceUntilIdle()

        assertNull(store.pendingRelease.value)
        assertEquals(emptyMap(), prefs.snapshot())
    }

    @Test
    fun persistedScreenshotModeSuppressesLaunchEvaluation() = runTest {
        val initial = mapOf(
            PersistenceKeys.HAS_COMPLETED_ONBOARDING to true,
            PersistenceKeys.IS_SCREENSHOT_MODE_ENABLED to true,
        )
        val prefs = InMemoryPreferencesStore(initial)
        val store = WhatsNewStore(prefs, this)

        store.evaluateStoredInstallState()
        advanceUntilIdle()

        assertNull(store.pendingRelease.value)
        assertEquals(initial, prefs.snapshot())
    }

    @Test
    fun markingAnEmptyWhatsNewAsShownDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = WhatsNewStore(prefs, this)
        assertNull(store.pendingRelease.value)
        assertEquals(emptyMap(), prefs.snapshot())

        store.markShown()
        advanceUntilIdle()

        assertNull(store.pendingRelease.value)
        assertEquals(emptyMap(), prefs.snapshot())
    }
}

package one.meshcore.shared.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DemoModeStoreTest {
    @Test
    fun threeTapsUnlockAndEnable() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val store = DemoModeStore(prefs, TestScope(UnconfinedTestDispatcher()))
        assertFalse(store.registerUnlockTap())
        assertFalse(store.registerUnlockTap())
        assertTrue(store.registerUnlockTap())
        assertTrue(store.isUnlocked.value)
        assertTrue(store.isEnabled.value)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.IS_DEMO_MODE_UNLOCKED))
        assertEquals(true, prefs.getBoolean(PersistenceKeys.IS_DEMO_MODE_ENABLED))
    }

    @Test
    fun cannotEnableWhenLocked() = runTest(UnconfinedTestDispatcher()) {
        val store = DemoModeStore(InMemoryPreferencesStore(), TestScope(UnconfinedTestDispatcher()))
        store.setEnabled(true)
        assertFalse(store.isEnabled.value)
    }

    @Test
    fun enablingScreenshotModeWhileLockedDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val store = DemoModeStore(prefs, this)
        assertFalse(store.isScreenshotModeEnabled.value)

        store.setScreenshotModeEnabled(true)
        advanceUntilIdle()

        assertFalse(store.isScreenshotModeEnabled.value)
        assertFalse(store.isEnabled.value)
        assertFalse(store.isUnlocked.value)
        assertEquals(emptyMap(), prefs.snapshot())
    }

    @Test
    fun turningOnDemoModeWhileLockedDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val store = DemoModeStore(prefs, this)
        assertFalse(store.isEnabled.value)
        assertFalse(store.isUnlocked.value)
        assertFalse(store.isScreenshotModeEnabled.value)

        store.setEnabled(true)
        advanceUntilIdle()

        assertFalse(store.isEnabled.value)
        assertFalse(store.isUnlocked.value)
        assertFalse(store.isScreenshotModeEnabled.value)
        assertEquals(emptyMap(), prefs.snapshot())
    }

    @Test
    fun turningDemoModeOffWhileLockedDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val store = DemoModeStore(prefs, this)
        assertFalse(store.isEnabled.value)
        assertFalse(store.isUnlocked.value)
        assertFalse(store.isScreenshotModeEnabled.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setEnabled(false)
        advanceUntilIdle()

        assertFalse(store.isEnabled.value)
        assertFalse(store.isUnlocked.value)
        assertFalse(store.isScreenshotModeEnabled.value)
        assertEquals(
            mapOf(PersistenceKeys.IS_DEMO_MODE_ENABLED to false),
            prefs.snapshot(),
        )
    }

    @Test
    fun turningScreenshotModeOffWhileLockedDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val store = DemoModeStore(prefs, this)
        assertFalse(store.isScreenshotModeEnabled.value)
        assertFalse(store.isEnabled.value)
        assertFalse(store.isUnlocked.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setScreenshotModeEnabled(false)
        advanceUntilIdle()

        assertFalse(store.isScreenshotModeEnabled.value)
        assertFalse(store.isEnabled.value)
        assertFalse(store.isUnlocked.value)
        assertEquals(
            mapOf(PersistenceKeys.IS_SCREENSHOT_MODE_ENABLED to false),
            prefs.snapshot(),
        )
    }

    @Test
    fun resettingDemoUnlockTapsDoesNotRadio() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        val store = DemoModeStore(prefs, this)
        assertFalse(store.registerUnlockTap())
        assertFalse(store.registerUnlockTap())
        assertFalse(store.isUnlocked.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.resetUnlockTaps()
        assertFalse(store.registerUnlockTap())
        advanceUntilIdle()

        assertFalse(store.isUnlocked.value)
        assertFalse(store.isEnabled.value)
        assertFalse(store.isScreenshotModeEnabled.value)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun unlockWritesOnlyTheDemoFlags() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore()
        DemoModeStore(prefs, TestScope(UnconfinedTestDispatcher())).unlock()

        // iOS `DemoModeManager.unlock()` sets two booleans and grants no entitlement; demo mode has no
        // entitlement seam here either, so a secret tap sequence cannot unlock paid themes.
        assertEquals(
            setOf(PersistenceKeys.IS_DEMO_MODE_UNLOCKED, PersistenceKeys.IS_DEMO_MODE_ENABLED),
            prefs.snapshot().keys,
        )
    }

    @Test
    fun loadRestoresFlags() = runTest(UnconfinedTestDispatcher()) {
        val prefs = InMemoryPreferencesStore(
            mapOf(
                PersistenceKeys.IS_DEMO_MODE_UNLOCKED to true,
                PersistenceKeys.IS_DEMO_MODE_ENABLED to true,
            ),
        )
        val store = DemoModeStore(prefs, TestScope(UnconfinedTestDispatcher()))
        store.load()
        assertTrue(store.isUnlocked.value)
        assertTrue(store.isEnabled.value)
    }
}

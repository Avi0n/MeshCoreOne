package one.meshcore.shared.map

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapDisplayPreferencesNorthLockTest {
    @Test
    fun defaultsToUnlocked() = runTest {
        val store = MapDisplayPreferencesStore(InMemoryPreferencesStore(), backgroundScope)
        assertFalse(store.mapNorthLocked.value)
    }

    @Test
    fun loadRestoresStoredValue() = runTest {
        val prefs = InMemoryPreferencesStore()
        prefs.putBoolean(PersistenceKeys.MAP_NORTH_LOCKED, true)
        val store = MapDisplayPreferencesStore(prefs, backgroundScope)
        store.load()
        testScheduler.runCurrent()
        assertTrue(store.mapNorthLocked.value)
    }

    @Test
    fun setUpdatesFlowAndPersists() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, backgroundScope)
        store.setMapNorthLocked(true)
        assertTrue(store.mapNorthLocked.value)
        testScheduler.runCurrent()
        assertEquals(true, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))
    }
}

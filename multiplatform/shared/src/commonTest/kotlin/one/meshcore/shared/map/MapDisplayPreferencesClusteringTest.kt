package one.meshcore.shared.map

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapDisplayPreferencesClusteringTest {
    @Test
    fun defaultsToClusteringOn() = runTest {
        val store = MapDisplayPreferencesStore(InMemoryPreferencesStore(), backgroundScope)
        assertTrue(store.mapClusteringEnabled.value)
    }

    @Test
    fun loadRestoresStoredValue() = runTest {
        val prefs = InMemoryPreferencesStore()
        prefs.putBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED, false)
        val store = MapDisplayPreferencesStore(prefs, backgroundScope)
        store.load()
        testScheduler.runCurrent()
        assertFalse(store.mapClusteringEnabled.value)
    }

    @Test
    fun setUpdatesFlowAndPersists() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, backgroundScope)
        store.setMapClusteringEnabled(false)
        assertFalse(store.mapClusteringEnabled.value)
        testScheduler.runCurrent()
        assertEquals(false, prefs.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
    }
}

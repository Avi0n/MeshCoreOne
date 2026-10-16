package one.meshcore.shared.map

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.theme.AppColorSchemePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapDisplayPreferencesStoreTest {
    @Test
    fun defaultsWhenNothingPersisted() = runTest {
        val store = MapDisplayPreferencesStore(InMemoryPreferencesStore(), this)
        store.load()
        advanceUntilIdle()

        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(false, store.mapNorthLocked.value)
    }

    @Test
    fun keepingTheStandardMapStyleDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))

        store.setMapStyle(MapStyleSelection.STANDARD)
        advanceUntilIdle()

        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(MapStyleSelection.STANDARD.rawValue, prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(false, store.mapNorthLocked.value)
    }

    @Test
    fun setterPersistsAndReloads() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)

        store.setShowDiscoveredNodesOnMap(true)
        store.setMapColorScheme(AppColorSchemePreference.DARK)
        store.setMapStyle(MapStyleSelection.TOPO)
        store.setMapShowLabels(false)
        store.setMapClusteringEnabled(false)
        store.setMapNorthLocked(true)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP))
        assertEquals("dark", prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals("topo", prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
        assertEquals(false, prefs.getBoolean(PersistenceKeys.MAP_SHOW_LABELS))
        assertEquals(false, prefs.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
        assertEquals(true, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))

        val reloaded = MapDisplayPreferencesStore(prefs, this)
        reloaded.load()
        advanceUntilIdle()

        assertEquals(true, reloaded.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.DARK, reloaded.mapColorScheme.value)
        assertEquals(MapStyleSelection.TOPO, reloaded.mapStyle.value)
        assertEquals(false, reloaded.mapShowLabels.value)
        assertEquals(false, reloaded.mapClusteringEnabled.value)
        assertEquals(true, reloaded.mapNorthLocked.value)
    }

    @Test
    fun loadReadsExistingRawValues() = runTest {
        val prefs = InMemoryPreferencesStore(
            mapOf(
                PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP to true,
                PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE to "light",
                PersistenceKeys.MAP_STYLE_SELECTION to "satellite",
            ),
        )
        val store = MapDisplayPreferencesStore(prefs, this)
        store.load()
        advanceUntilIdle()

        assertEquals(true, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.LIGHT, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.SATELLITE, store.mapStyle.value)
    }

    @Test
    fun mapStyleFromRawFallsBackToStandard() {
        assertEquals(MapStyleSelection.STANDARD, mapStyleFromRaw(null))
        assertEquals(MapStyleSelection.STANDARD, mapStyleFromRaw("nope"))
        assertEquals(MapStyleSelection.TOPO, mapStyleFromRaw("topo"))
    }

    @Test
    fun keepingMapClusteringOnDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setMapClusteringEnabled(true)
        advanceUntilIdle()

        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_SHOW_LABELS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP))
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
        assertEquals(
            mapOf(PersistenceKeys.MAP_CLUSTERING_ENABLED to true),
            prefs.snapshot(),
        )
    }

    @Test
    fun keepingMapLabelsOnDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setMapShowLabels(true)
        advanceUntilIdle()

        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, prefs.getBoolean(PersistenceKeys.MAP_SHOW_LABELS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP))
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
        assertEquals(
            mapOf(PersistenceKeys.MAP_SHOW_LABELS to true),
            prefs.snapshot(),
        )
    }

    @Test
    fun keepingMapNorthLockOffDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setMapNorthLocked(false)
        advanceUntilIdle()

        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))
        assertEquals(
            mapOf(PersistenceKeys.MAP_NORTH_LOCKED to false),
            prefs.snapshot(),
        )
    }

    @Test
    fun turningOnMapNorthLockDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)

        store.setMapNorthLocked(true)

        assertEquals(true, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))
        assertEquals(
            mapOf(PersistenceKeys.MAP_NORTH_LOCKED to true),
            prefs.snapshot(),
        )
    }

    @Test
    fun keepingTheSystemMapColorsDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setMapColorScheme(AppColorSchemePreference.SYSTEM)
        advanceUntilIdle()

        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals("system", prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_SHOW_LABELS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP))
        assertEquals(
            mapOf(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE to "system"),
            prefs.snapshot(),
        )
    }

    @Test
    fun choosingAMapColorSchemeDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)

        store.setMapColorScheme(AppColorSchemePreference.DARK)
        assertEquals(AppColorSchemePreference.DARK, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        advanceUntilIdle()

        assertEquals("dark", prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(
            mapOf(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE to "dark"),
            prefs.snapshot(),
        )
    }

    @Test
    fun choosingAMapStyleDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)

        store.setMapStyle(MapStyleSelection.SATELLITE)
        assertEquals(MapStyleSelection.SATELLITE, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        advanceUntilIdle()

        assertEquals("satellite", prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
        assertEquals(
            mapOf(PersistenceKeys.MAP_STYLE_SELECTION to "satellite"),
            prefs.snapshot(),
        )
    }

    @Test
    fun keepingDiscoveredNodesOffTheMapDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertTrue(prefs.snapshot().isEmpty())

        store.setShowDiscoveredNodesOnMap(false)
        advanceUntilIdle()

        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(false, prefs.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_SHOW_LABELS))
        assertEquals(null, prefs.getBoolean(PersistenceKeys.MAP_NORTH_LOCKED))
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_COLOR_SCHEME_PREFERENCE))
        assertEquals(null, prefs.getString(PersistenceKeys.MAP_STYLE_SELECTION))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP to false),
            prefs.snapshot(),
        )
    }

    @Test
    fun showingDiscoveredNodesOnTheMapDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)

        store.setShowDiscoveredNodesOnMap(true)
        assertEquals(true, store.showDiscoveredNodesOnMap.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        advanceUntilIdle()

        assertEquals(true, prefs.getBoolean(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP))
        assertEquals(
            mapOf(PersistenceKeys.SHOW_DISCOVERED_NODES_ON_MAP to true),
            prefs.snapshot(),
        )
    }

    @Test
    fun turningOffMapLabelsDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)

        store.setMapShowLabels(false)
        assertEquals(false, store.mapShowLabels.value)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(PersistenceKeys.MAP_SHOW_LABELS))
        assertEquals(
            mapOf(PersistenceKeys.MAP_SHOW_LABELS to false),
            prefs.snapshot(),
        )
    }

    @Test
    fun turningOffMapClusteringDoesNotRadio() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = MapDisplayPreferencesStore(prefs, this)
        assertEquals(true, store.mapClusteringEnabled.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)

        store.setMapClusteringEnabled(false)
        assertEquals(false, store.mapClusteringEnabled.value)
        assertEquals(true, store.mapShowLabels.value)
        assertEquals(MapStyleSelection.STANDARD, store.mapStyle.value)
        assertEquals(AppColorSchemePreference.SYSTEM, store.mapColorScheme.value)
        assertEquals(false, store.mapNorthLocked.value)
        assertEquals(false, store.showDiscoveredNodesOnMap.value)
        advanceUntilIdle()

        assertEquals(false, prefs.getBoolean(PersistenceKeys.MAP_CLUSTERING_ENABLED))
        assertEquals(
            mapOf(PersistenceKeys.MAP_CLUSTERING_ENABLED to false),
            prefs.snapshot(),
        )
    }
}

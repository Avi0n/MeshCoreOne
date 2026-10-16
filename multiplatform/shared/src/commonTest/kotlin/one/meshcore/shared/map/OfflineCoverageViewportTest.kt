package one.meshcore.shared.map

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineCoverageViewportTest {
    private val seattle = CoordinateBounds(sw = LatLng(47.4, -122.5), ne = LatLng(47.8, -122.1))
    private val denver = CoordinateBounds(sw = LatLng(39.5, -105.2), ne = LatLng(39.9, -104.8))

    private fun pack(bounds: CoordinateBounds?, layer: OfflineMapLayer = OfflineMapLayer.TOPO) = OfflineMapPack(
        id = "p-${bounds?.sw?.latitude}",
        name = "Region",
        layer = layer,
        createdAtEpochSeconds = 1,
        completed = true,
        bounds = bounds,
    )

    @Test
    fun boxesOverlapOnlyWhenTheyShareArea() {
        assertTrue(seattle.overlaps(seattle))
        assertFalse(seattle.overlaps(denver))
        assertFalse(denver.overlaps(seattle))
        // Edge contact counts as overlap, matching the iOS predicate.
        val touching = CoordinateBounds(sw = LatLng(47.8, -122.1), ne = LatLng(48.2, -121.7))
        assertTrue(seattle.overlaps(touching))
    }

    @Test
    fun aPackForAnotherRegionDoesNotCoverTheViewport() {
        val registry = OfflineMapPackRegistry()
        registry.upsert(pack(denver))
        assertFalse(
            registry.hasCompletedPack(OfflineMapLayer.TOPO, seattle),
            "a topo pack downloaded for Denver must not report coverage over Seattle",
        )
        assertTrue(registry.hasCompletedPack(OfflineMapLayer.TOPO, denver))
    }

    @Test
    fun noViewportAsksTheLayerWideQuestion() {
        val registry = OfflineMapPackRegistry()
        registry.upsert(pack(denver))
        assertTrue(registry.hasCompletedPack(OfflineMapLayer.TOPO))
        assertTrue(registry.hasCompletedPack(OfflineMapLayer.TOPO, viewport = null))
    }

    @Test
    fun anUnfinishedOrBoundlessPackBacksNoViewport() {
        val registry = OfflineMapPackRegistry()
        registry.upsert(pack(bounds = null))
        assertFalse(registry.hasCompletedPack(OfflineMapLayer.TOPO, seattle))
        registry.upsert(pack(seattle).copy(id = "partial", completed = false))
        assertFalse(registry.hasCompletedPack(OfflineMapLayer.TOPO, seattle))
        registry.upsert(pack(seattle))
        assertTrue(registry.hasCompletedPack(OfflineMapLayer.TOPO, seattle))
    }

    @Test
    fun offlineTopoStaysDisabledOverAnUncoveredViewport() {
        val registry = OfflineMapPackRegistry()
        registry.upsert(pack(denver))
        val reason = MapStyleGate.disabledReason(
            style = MapStyleSelection.TOPO,
            isNetworkAvailable = false,
            hasOfflineCoverage = registry.hasCompletedPack(MapStyleSelection.TOPO.offlineMapLayer, seattle),
        )
        assertEquals(MapStyleDisabledReason.NO_OFFLINE_COVERAGE, reason)
    }

    @Test
    fun packBoundsSurvivePrefsRoundTrip() = runTest {
        val prefs = InMemoryPreferencesStore()
        val registry = OfflineMapPackRegistry()
        registry.upsert(pack(seattle))
        registry.saveToPrefs(prefs)
        val loaded = OfflineMapPackRegistry()
        loaded.loadFromPrefs(prefs)
        assertEquals(seattle, loaded.list().first().bounds)
        assertTrue(loaded.hasCompletedPack(OfflineMapLayer.TOPO, seattle))
    }

    @Test
    fun aLegacyRecordWithoutBoundsStillDecodes() = runTest {
        val prefs = InMemoryPreferencesStore()
        prefs.putString(
            OFFLINE_MAP_PACKS_PREFS_KEY,
            """[{"id":"old","name":"Old","layer":"topo","createdAtEpochSeconds":5,"completed":true}]""",
        )
        val loaded = OfflineMapPackRegistry()
        loaded.loadFromPrefs(prefs)
        assertEquals(1, loaded.list().size)
        assertEquals(null, loaded.list().first().bounds)
        assertTrue(loaded.hasCompletedPack(OfflineMapLayer.TOPO))
    }
}

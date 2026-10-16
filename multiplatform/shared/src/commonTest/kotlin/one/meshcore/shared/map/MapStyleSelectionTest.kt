package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals

/** Style gating and URL selection: which styles need network, their offline layer, and the dark/offline URL rule. */
class MapStyleSelectionTest {
    @Test
    fun fromRawRoundTripsPinnedCaseNames() {
        assertEquals(MapStyleSelection.STANDARD, MapStyleSelection.fromRaw("standard"))
        assertEquals(MapStyleSelection.SATELLITE, MapStyleSelection.fromRaw("satellite"))
        assertEquals(MapStyleSelection.TOPO, MapStyleSelection.fromRaw("topo"))
        assertEquals(null, MapStyleSelection.fromRaw("unknown"))
        assertEquals(null, MapStyleSelection.fromRaw(""))
    }

    @Test
    fun onlySatelliteRequiresNetwork() {
        assertEquals(false, MapStyleSelection.STANDARD.requiresNetwork)
        assertEquals(true, MapStyleSelection.SATELLITE.requiresNetwork)
        assertEquals(false, MapStyleSelection.TOPO.requiresNetwork)
    }

    @Test
    fun offlineLayerMapsStandardAndSatelliteToBaseAndTopoToTopo() {
        assertEquals(OfflineMapLayer.BASE, MapStyleSelection.STANDARD.offlineMapLayer)
        assertEquals(OfflineMapLayer.BASE, MapStyleSelection.SATELLITE.offlineMapLayer)
        assertEquals(OfflineMapLayer.TOPO, MapStyleSelection.TOPO.offlineMapLayer)
    }

    @Test
    fun styleUrlUsesDarkOnlyWhenDarkModeAndOnline() {
        assertEquals(
            MapTileUrls.OPEN_FREE_MAP_DARK,
            MapStyleSelection.STANDARD.styleUrl(isDarkMode = true, isOffline = false),
        )
        assertEquals(
            MapTileUrls.OPEN_FREE_MAP_LIBERTY,
            MapStyleSelection.STANDARD.styleUrl(isDarkMode = false, isOffline = false),
        )
    }

    @Test
    fun styleUrlIsAlwaysLibertyWhenOffline() {
        // Offline packs are downloaded against Liberty; dark is suppressed offline so cached tiles resolve.
        assertEquals(
            MapTileUrls.OPEN_FREE_MAP_LIBERTY,
            MapStyleSelection.TOPO.styleUrl(isDarkMode = true, isOffline = true),
        )
    }
}

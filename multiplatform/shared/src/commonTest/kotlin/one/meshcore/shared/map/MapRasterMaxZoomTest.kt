package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapRasterMaxZoomTest {
    @Test
    fun rasterOverlaysArePinnedToTheirProviderCeilings() {
        // Unpinned, the source keeps asking for tiles the provider does not have and the imagery goes
        // blank exactly when a responder zooms in on a node.
        assertEquals(19, MapStyleSelection.SATELLITE.rasterMaxZoom())
        assertEquals(17, MapStyleSelection.TOPO.rasterMaxZoom())
    }

    @Test
    fun theVectorStyleHasNoRasterCeiling() {
        assertNull(MapStyleSelection.STANDARD.rasterMaxZoom())
    }

    @Test
    fun topoIsCappedBelowSatellite() {
        assertEquals(MapRasterMaxZoom.SATELLITE, MapStyleSelection.SATELLITE.rasterMaxZoom())
        assertEquals(MapRasterMaxZoom.TOPO, MapStyleSelection.TOPO.rasterMaxZoom())
        assertEquals(true, MapRasterMaxZoom.TOPO < MapRasterMaxZoom.SATELLITE)
    }
}

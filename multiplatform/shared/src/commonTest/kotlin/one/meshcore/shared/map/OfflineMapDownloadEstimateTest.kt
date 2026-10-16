package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineMapDownloadEstimateTest {
    @Test
    fun estimatedSizeIncludesOverheadAndGrowsWithSpan() {
        val small = CoordinateBounds(
            sw = LatLng(37.0, -122.5),
            ne = LatLng(37.5, -122.0),
        )
        val large = CoordinateBounds(
            sw = LatLng(30.0, -130.0),
            ne = LatLng(45.0, -110.0),
        )
        val smallBytes = OfflineMapDownloadEstimate.estimatedDownloadSize(small, OfflineMapLayer.BASE)
        val largeBytes = OfflineMapDownloadEstimate.estimatedDownloadSize(large, OfflineMapLayer.BASE)
        assertTrue(smallBytes >= OfflineMapDownloadEstimate.OVERHEAD_BYTES)
        assertTrue(largeBytes > smallBytes)
    }

    @Test
    fun topoAddsToBaseWhenBothLayersSelected() {
        val bounds = CoordinateBounds(
            sw = LatLng(40.0, -75.0),
            ne = LatLng(41.0, -74.0),
        )
        val baseOnly = OfflineMapDownloadEstimate.estimatedDownloadSize(
            bounds,
            setOf(OfflineMapLayer.BASE),
        )
        val both = OfflineMapDownloadEstimate.estimatedDownloadSize(
            bounds,
            setOf(OfflineMapLayer.BASE, OfflineMapLayer.TOPO),
        )
        assertTrue(both > baseOnly)
    }

    @Test
    fun selectionBoundsInsetsCameraSpan() {
        val region = MapCameraRegion(
            center = LatLng(10.0, 20.0),
            spanLatDeg = 10.0,
            spanLonDeg = 20.0,
        )
        val bounds = assertNotNull(OfflineMapDownloadEstimate.selectionBounds(region))
        // Full half-spans would be 5/10; inset 12% of span on each edge → half - 1.2 / 2.4
        assertEquals(10.0 - 5.0 + 1.2, bounds.sw.latitude, absoluteTolerance = 1e-9)
        assertEquals(10.0 + 5.0 - 1.2, bounds.ne.latitude, absoluteTolerance = 1e-9)
        assertEquals(20.0 - 10.0 + 2.4, bounds.sw.longitude, absoluteTolerance = 1e-9)
        assertEquals(20.0 + 10.0 - 2.4, bounds.ne.longitude, absoluteTolerance = 1e-9)
    }

    @Test
    fun selectionBoundsRejectsInvalidRegion() {
        assertNull(
            OfflineMapDownloadEstimate.selectionBounds(
                MapCameraRegion(LatLng(91.0, 0.0), 1.0, 1.0),
            ),
        )
        assertNull(
            OfflineMapDownloadEstimate.selectionBounds(
                MapCameraRegion(LatLng(0.0, 0.0), 0.0, 1.0),
            ),
        )
    }

    @Test
    fun expandForDownloadEnforcesMinimumSpan() {
        val tight = MapCameraRegion(LatLng(1.0, 2.0), spanLatDeg = 0.01, spanLonDeg = 0.02)
        val expanded = OfflineMapDownloadEstimate.expandForDownload(tight)
        assertEquals(OfflineMapDownloadEstimate.MIN_OFFLINE_SPAN_DEG, expanded.spanLatDeg)
        assertEquals(OfflineMapDownloadEstimate.MIN_OFFLINE_SPAN_DEG, expanded.spanLonDeg)
    }

    @Test
    fun buildOfflinePacksNamesLayersWhenBothSelected() {
        val packs = buildOfflinePacks(
            name = "Home",
            layers = offlineLayers(includeTopo = true),
            createdAtEpochSeconds = 100,
        )
        assertEquals(2, packs.size)
        assertEquals("Home (base)", packs[0].name)
        assertEquals(OfflineMapLayer.BASE, packs[0].layer)
        assertEquals("Home (topo)", packs[1].name)
        assertEquals(OfflineMapLayer.TOPO, packs[1].layer)
    }

    @Test
    fun buildOfflinePacksSingleLayerKeepsBareName() {
        val packs = buildOfflinePacks(
            name = "  Field  ",
            layers = offlineLayers(includeTopo = false),
            createdAtEpochSeconds = 50,
        )
        assertEquals(1, packs.size)
        assertEquals("Field", packs[0].name)
        assertEquals(OfflineMapLayer.BASE, packs[0].layer)
    }

    @Test
    fun formatApproximateSizeUsesSiSteps() {
        assertEquals("500 B", OfflineMapDownloadEstimate.formatApproximateSize(500))
        assertEquals("1.5 KB", OfflineMapDownloadEstimate.formatApproximateSize(1_500))
        assertEquals("12 MB", OfflineMapDownloadEstimate.formatApproximateSize(12_000_000))
    }

    @Test
    fun tileCountAtZoomIsAtLeastOne() {
        val pointish = CoordinateBounds(
            sw = LatLng(37.77, -122.42),
            ne = LatLng(37.78, -122.41),
        )
        assertTrue(OfflineMapDownloadEstimate.tileCountAtZoom(pointish, 10) >= 1)
    }
}

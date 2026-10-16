package one.meshcore.shared.map

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.settings_offline_maps_error_tile_limit_reached
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineMapTileDownloaderTest {
    @Test
    fun `base layer max zoom is 14`() {
        assertEquals(14.0, OfflineMapLayer.BASE.maxDownloadZoom())
    }

    @Test
    fun `topo layer max zoom is 17`() {
        assertEquals(17.0, OfflineMapLayer.TOPO.maxDownloadZoom())
    }

    @Test
    fun `offline style uses liberty base`() {
        assertEquals(MapTileUrls.OPEN_FREE_MAP_LIBERTY, OfflineMapLayer.BASE.styleUrlForOffline())
        // A topo pack has to cache the raster the topo style draws; Liberty here meant the style gate
        // reported topo coverage over a pack containing only base tiles.
        assertEquals(TOPO_OFFLINE_STYLE_ASSET, OfflineMapLayer.TOPO.styleUrlForOffline())
    }

    @Test
    fun `no-op downloader reports unavailable`() {
        var error: String? = null
        NoOpOfflineMapTileDownloader().startDownload(
            pack = OfflineMapPack(
                id = "p1",
                name = "R1",
                layer = OfflineMapLayer.BASE,
                createdAtEpochSeconds = 0L,
            ),
            bounds = CoordinateBounds(LatLng(-1.0, -1.0), LatLng(1.0, 1.0)),
            onUpdate = {},
            onError = { error = it },
        )
        assertEquals(OFFLINE_MAP_UNAVAILABLE_TOKEN, error)
    }

    @Test
    fun tileLimitTokenMapsToLocalizedTileLimitReached() {
        assertEquals(
            Res.string.settings_offline_maps_error_tile_limit_reached,
            offlineMapDownloadErrorResource(OFFLINE_MAP_TILE_LIMIT_TOKEN),
        )
        assertEquals(null, offlineMapDownloadErrorResource("some sdk error"))
    }

    @Test
    fun blankCreateAndDownloadFallbacksMapToAnErrorOccurred() {
        assertEquals(
            Res.string.contacts_common_error_occurred,
            offlineMapDownloadErrorResource(OFFLINE_MAP_CREATE_FAILED_TOKEN),
        )
        assertEquals(
            Res.string.contacts_common_error_occurred,
            offlineMapDownloadErrorResource(OFFLINE_MAP_DOWNLOAD_FAILED_TOKEN),
        )
        assertEquals(
            Res.string.contacts_common_error_occurred,
            offlineMapDownloadErrorResource(OFFLINE_MAP_UNAVAILABLE_TOKEN),
        )
    }
}

package one.meshcore.shared.map

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.settings_offline_maps_error_tile_limit_reached
import org.jetbrains.compose.resources.StringResource

/**
 * Starts a MapLibre offline tile pack for [pack] covering [bounds]. Progress and completion update the
 * registry via [onUpdate]. Desktop / tests use [NoOpOfflineMapTileDownloader].
 */
interface OfflineMapTileDownloader {
    fun startDownload(
        pack: OfflineMapPack,
        bounds: CoordinateBounds,
        onUpdate: (OfflineMapPack) -> Unit,
        onError: (String) -> Unit,
    )

    /** Suspend an in-flight download (MapLibre STATE_INACTIVE). */
    fun pauseDownload(packId: String)

    /** Resume a paused or process-restored region (MapLibre STATE_ACTIVE). */
    fun resumeDownload(pack: OfflineMapPack, onUpdate: (OfflineMapPack) -> Unit, onError: (String) -> Unit)

    fun deleteNativePack(packId: String)

    companion object {
        /** Default min zoom for offline packs (matches iOS OfflineMapService default). */
        const val DEFAULT_MIN_ZOOM = 10.0

        const val MAX_ZOOM_BASE = 14.0
        const val MAX_ZOOM_TOPO = 17.0
    }
}

/** No-op until a platform hosts MapLibre OfflineManager (Android only for now). */
class NoOpOfflineMapTileDownloader : OfflineMapTileDownloader {
    override fun startDownload(
        pack: OfflineMapPack,
        bounds: CoordinateBounds,
        onUpdate: (OfflineMapPack) -> Unit,
        onError: (String) -> Unit,
    ) {
        onError(OFFLINE_MAP_UNAVAILABLE_TOKEN)
    }

    override fun pauseDownload(packId: String) = Unit

    override fun resumeDownload(pack: OfflineMapPack, onUpdate: (OfflineMapPack) -> Unit, onError: (String) -> Unit) {
        // Desktop / tests have no OfflineManager.
    }

    override fun deleteNativePack(packId: String) = Unit
}

fun OfflineMapLayer.maxDownloadZoom(): Double = when (this) {
    OfflineMapLayer.BASE -> OfflineMapTileDownloader.MAX_ZOOM_BASE
    OfflineMapLayer.TOPO -> OfflineMapTileDownloader.MAX_ZOOM_TOPO
}

fun OfflineMapLayer.styleUrlForOffline(): String = when (this) {
    OfflineMapLayer.BASE -> MapTileUrls.OPEN_FREE_MAP_LIBERTY
    // A topo pack must cache the OpenTopoMap raster the topo style actually draws. Downloading the
    // Liberty base instead left the style gate reporting topo coverage over a pack with no topo tiles,
    // so offline the map read "Topo" and drew a plain base map — and the base was paid for twice.
    OfflineMapLayer.TOPO -> TOPO_OFFLINE_STYLE_ASSET
}

/** The bundled topo style, mirroring the iOS `topo-offline.json` bundle resource byte for byte. */
const val TOPO_OFFLINE_STYLE_ASSET: String = "asset://topo-offline.json"

/**
 * Token the Android MapLibre observer reports when the tile count cap trips. The host maps it to
 * `settings_offline_maps_error_tile_limit_reached`, matching iOS `OfflineMaps.Error.tileLimitReached`.
 */
const val OFFLINE_MAP_TILE_LIMIT_TOKEN: String = "offline-map-tile-limit-reached"

/** Create-region failed with a blank SDK string. */
const val OFFLINE_MAP_CREATE_FAILED_TOKEN: String = "offline-map-create-failed"

/** Download observer failed with a blank SDK string. */
const val OFFLINE_MAP_DOWNLOAD_FAILED_TOKEN: String = "offline-map-download-failed"

/** Desktop / tests have no OfflineManager. */
const val OFFLINE_MAP_UNAVAILABLE_TOKEN: String = "offline-map-unavailable"

fun offlineMapDownloadErrorResource(raw: String): StringResource? = when (raw) {
    OFFLINE_MAP_TILE_LIMIT_TOKEN -> Res.string.settings_offline_maps_error_tile_limit_reached
    OFFLINE_MAP_CREATE_FAILED_TOKEN,
    OFFLINE_MAP_DOWNLOAD_FAILED_TOKEN,
    OFFLINE_MAP_UNAVAILABLE_TOKEN,
    -> Res.string.contacts_common_error_occurred
    else -> null
}

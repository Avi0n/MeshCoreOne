package one.meshcore.shared.map.ui

import androidx.compose.runtime.getValue
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.MapTileUrls
import one.meshcore.shared.map.rasterMaxZoom
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Toggles the satellite/topo raster overlays for the current [mapStyleSelection] (added once per
 * style load by [setupRasterSources]). Same base vector style regardless of selection — only layer
 * visibility changes — mirroring iOS `updateRasterLayerVisibility`.
 */
internal fun MapLibreHostState.updateRasterVisibility(map: MapLibreMap) {
    map.style?.let { updateRasterVisibility(it) }
}

internal fun MapLibreHostState.setupRasterSources(style: Style) {
    if (style.getSource(SATELLITE_SOURCE_ID) == null) {
        val tiles = TileSet(TILEJSON_VERSION, MapTileUrls.ESRI_WORLD_IMAGERY)
        tiles.attribution = ESRI_ATTRIBUTION
        // Pinned to the provider's deepest level so MapLibre overzooms instead of requesting tiles
        // that do not exist and leaving the imagery blank.
        MapStyleSelection.SATELLITE.rasterMaxZoom()?.let { tiles.maxZoom = it.toFloat() }
        style.addSource(RasterSource(SATELLITE_SOURCE_ID, tiles, RASTER_TILE_SIZE))
        style.addLayer(
            RasterLayer(SATELLITE_LAYER_ID, SATELLITE_SOURCE_ID).withProperties(MapPinLayers.visibility(false)),
        )
    }
    if (style.getSource(TOPO_SOURCE_ID) == null) {
        val tiles = TileSet(
            TILEJSON_VERSION,
            MapTileUrls.OPEN_TOPO_MAP_A,
            MapTileUrls.OPEN_TOPO_MAP_B,
            MapTileUrls.OPEN_TOPO_MAP_C,
        )
        tiles.attribution = OPEN_TOPO_MAP_ATTRIBUTION
        MapStyleSelection.TOPO.rasterMaxZoom()?.let { tiles.maxZoom = it.toFloat() }
        style.addSource(RasterSource(TOPO_SOURCE_ID, tiles, RASTER_TILE_SIZE))
        style.addLayer(RasterLayer(TOPO_LAYER_ID, TOPO_SOURCE_ID).withProperties(MapPinLayers.visibility(false)))
    }
}

internal fun MapLibreHostState.updateRasterVisibility(style: Style) {
    style.getLayer(SATELLITE_LAYER_ID)
        ?.setProperties(MapPinLayers.visibility(mapStyleSelection == MapStyleSelection.SATELLITE))
    style.getLayer(TOPO_LAYER_ID)
        ?.setProperties(MapPinLayers.visibility(mapStyleSelection == MapStyleSelection.TOPO))
}

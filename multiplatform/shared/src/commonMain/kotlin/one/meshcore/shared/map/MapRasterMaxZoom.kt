package one.meshcore.shared.map

/**
 * The provider tile ceilings for the raster overlays. Past its ceiling a provider has no tile to serve,
 * so a source pinned here lets MapLibre overzoom the deepest level it holds; an unpinned source keeps
 * requesting tiles that do not exist and the imagery goes blank exactly when a responder zooms in.
 * Mirrors the iOS `MLNRasterTileSource` `maximumZoomLevel` options.
 */
object MapRasterMaxZoom {
    const val SATELLITE = 19
    const val TOPO = 17
}

/** The raster overlay ceiling for a style, or null for a style that draws no raster overlay. */
fun MapStyleSelection.rasterMaxZoom(): Int? = when (this) {
    MapStyleSelection.STANDARD -> null
    MapStyleSelection.SATELLITE -> MapRasterMaxZoom.SATELLITE
    MapStyleSelection.TOPO -> MapRasterMaxZoom.TOPO
}

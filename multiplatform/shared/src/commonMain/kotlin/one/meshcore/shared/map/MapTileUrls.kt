package one.meshcore.shared.map

/**
 * The built-in tile endpoints. Mirrors iOS `MapTileURLs`. The two OpenFreeMap entries are full vector
 * style-JSON URLs; the raster entries carry `{z}/{x}/{y}` placeholders. Esri uses `{z}/{y}/{x}` order.
 * All raster sources are XYZ (Y increases southward) — the `{y}` placeholder is web-mercator, never TMS.
 */
object MapTileUrls {
    const val OPEN_FREE_MAP_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val OPEN_FREE_MAP_DARK = "https://tiles.openfreemap.org/styles/dark"
    const val ESRI_WORLD_IMAGERY =
        "https://server.arcgisonline.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
    const val OPEN_TOPO_MAP_A = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"
    const val OPEN_TOPO_MAP_B = "https://b.tile.opentopomap.org/{z}/{x}/{y}.png"
    const val OPEN_TOPO_MAP_C = "https://c.tile.opentopomap.org/{z}/{x}/{y}.png"
}

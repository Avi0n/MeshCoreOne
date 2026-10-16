package one.meshcore.shared.map

/**
 * The map style the user picks. Mirrors iOS `MapStyleSelection`. Raw values are pinned to the iOS case
 * names so the `mapStyleSelection` preference round-trips. `label` (a localized string) lives in the UI
 * layer; the model carries only the gating logic and the style URL.
 */
enum class MapStyleSelection(val rawValue: String) {
    STANDARD("standard"),
    SATELLITE("satellite"),
    TOPO("topo"),
    ;

    companion object {
        fun fromRaw(raw: String): MapStyleSelection? = entries.firstOrNull { it.rawValue == raw }
    }

    /** Whether the style needs live network (satellite raster has no offline pack). */
    val requiresNetwork: Boolean
        get() = when (this) {
            STANDARD -> false
            SATELLITE -> true
            TOPO -> false
        }

    /** The offline-pack layer whose coverage backs this style. */
    val offlineMapLayer: OfflineMapLayer
        get() = when (this) {
            STANDARD -> OfflineMapLayer.BASE
            SATELLITE -> OfflineMapLayer.BASE
            TOPO -> OfflineMapLayer.TOPO
        }

    /**
     * The base vector style URL. Satellite and topo add raster overlays at runtime on top of this same
     * base, so the URL depends only on dark mode and offline state, not the selected style. When
     * offline, always Liberty: offline packs are downloaded against the Liberty style and MapLibre
     * serves cached tiles only for the exact style URL used at download time. Mirrors iOS
     * `MapStyleSelection.styleURL`.
     */
    fun styleUrl(isDarkMode: Boolean, isOffline: Boolean = false): String {
        val useDark = isDarkMode && !isOffline
        return if (useDark) MapTileUrls.OPEN_FREE_MAP_DARK else MapTileUrls.OPEN_FREE_MAP_LIBERTY
    }
}

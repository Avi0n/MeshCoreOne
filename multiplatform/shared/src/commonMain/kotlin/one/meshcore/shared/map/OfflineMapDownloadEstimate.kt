package one.meshcore.shared.map

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * Offline pack download sizing and region-selection helpers.
 * Size table and tile-count math match iOS `OfflineMapService.estimatedDownloadSize`.
 */
object OfflineMapDownloadEstimate {
    /** Non-tile overhead (style JSON, sprites, glyphs) — iOS constant. */
    const val OVERHEAD_BYTES: Long = 500_000L

    /** Warn when the estimate exceeds this many bytes (iOS `largeDownloadThreshold`). */
    const val LARGE_DOWNLOAD_THRESHOLD_BYTES: Long = 500_000_000L

    /** Default min zoom for offline packs (matches [OfflineMapTileDownloader.DEFAULT_MIN_ZOOM]). */
    const val DEFAULT_MIN_ZOOM: Int = 10

    /**
     * Fraction of the camera span inset on each edge to approximate the iOS selection rectangle
     * (40pt padding) without requiring pixel geometry.
     */
    const val SELECTION_INSET_FRACTION: Double = 0.12

    /** Minimum usable pack span (degrees) so a tightly zoomed camera still downloads a region. */
    const val MIN_OFFLINE_SPAN_DEG: Double = 0.25

    /**
     * Default camera when the user has never opened Map: mid-continent metro-scale framing so the
     * picker never starts at (0,0) ocean tiles and the first estimate stays downloadable.
     */
    val DEFAULT_PICKER_CAMERA: MapCameraRegion = MapCameraRegion(
        center = LatLng(latitude = 39.5, longitude = -98.35),
        spanLatDeg = 1.5,
        spanLonDeg = 2.0,
    )

    fun maxZoom(layer: OfflineMapLayer): Int = when (layer) {
        OfflineMapLayer.BASE -> OfflineMapTileDownloader.MAX_ZOOM_BASE.toInt()
        OfflineMapLayer.TOPO -> OfflineMapTileDownloader.MAX_ZOOM_TOPO.toInt()
    }

    /**
     * Estimated download size using per-zoom average tile bytes, matching iOS
     * `OfflineMapService.estimatedDownloadSize`.
     */
    fun estimatedDownloadSize(
        bounds: CoordinateBounds,
        layer: OfflineMapLayer,
        minZoom: Int = DEFAULT_MIN_ZOOM,
        maxZoom: Int = maxZoom(layer),
    ): Long {
        if (minZoom > maxZoom) return OVERHEAD_BYTES
        val bytesPerTile = bytesPerTileTable(layer)
        var total = 0L
        for (z in minZoom..maxZoom) {
            val tileCount = tileCountAtZoom(bounds, z)
            total += tileCount * (bytesPerTile[z] ?: DEFAULT_BYTES_PER_TILE)
        }
        return total + OVERHEAD_BYTES
    }

    fun estimatedDownloadSize(
        bounds: CoordinateBounds,
        layers: Set<OfflineMapLayer>,
        minZoom: Int = DEFAULT_MIN_ZOOM,
    ): Long = layers.sumOf { layer ->
        estimatedDownloadSize(bounds = bounds, layer = layer, minZoom = minZoom, maxZoom = maxZoom(layer))
    }

    /**
     * Selection box from a camera region: center stays put, span shrinks by [SELECTION_INSET_FRACTION]
     * on each edge (mirrors iOS `RegionPickerSheet.selectionBounds` padding math).
     */
    fun selectionBounds(region: MapCameraRegion): CoordinateBounds? {
        if (!region.center.isValid) return null
        if (!region.spanLatDeg.isFinite() || !region.spanLonDeg.isFinite()) return null
        if (region.spanLatDeg <= 0.0 || region.spanLonDeg <= 0.0) return null
        val latInset = region.spanLatDeg * SELECTION_INSET_FRACTION
        val lonInset = region.spanLonDeg * SELECTION_INSET_FRACTION
        val halfLat = region.spanLatDeg / 2.0 - latInset
        val halfLon = region.spanLonDeg / 2.0 - lonInset
        if (halfLat <= 0.0 || halfLon <= 0.0) return null
        val swLat = (region.center.latitude - halfLat).coerceIn(MIN_LATITUDE, MAX_LATITUDE)
        val neLat = (region.center.latitude + halfLat).coerceIn(MIN_LATITUDE, MAX_LATITUDE)
        return CoordinateBounds(
            sw = LatLng(swLat, region.center.longitude - halfLon),
            ne = LatLng(neLat, region.center.longitude + halfLon),
        )
    }

    /**
     * Expand spans to at least [MIN_OFFLINE_SPAN_DEG] before download so a zoomed-in map still
     * produces a usable pack (same intent as AppContainer's former expand path).
     */
    fun expandForDownload(region: MapCameraRegion): MapCameraRegion = region.copy(
        spanLatDeg = max(region.spanLatDeg, MIN_OFFLINE_SPAN_DEG),
        spanLonDeg = max(region.spanLonDeg, MIN_OFFLINE_SPAN_DEG),
    )

    fun expandBoundsForDownload(bounds: CoordinateBounds): CoordinateBounds? {
        val centerLat = (bounds.sw.latitude + bounds.ne.latitude) / 2.0
        val centerLon = (bounds.sw.longitude + bounds.ne.longitude) / 2.0
        val spanLat = max(abs(bounds.ne.latitude - bounds.sw.latitude), MIN_OFFLINE_SPAN_DEG)
        val spanLon = max(abs(bounds.ne.longitude - bounds.sw.longitude), MIN_OFFLINE_SPAN_DEG)
        return MapCameraRegion(
            center = LatLng(centerLat, centerLon),
            spanLatDeg = spanLat,
            spanLonDeg = spanLon,
        ).validatedBounds()
    }

    /**
     * Human-readable approximate size for the estimate line (e.g. "12 MB").
     * Uses 1000-based SI steps so the UI reads like iOS `.byteCount(style: .file)`.
     */
    fun formatApproximateSize(bytes: Long): String {
        if (bytes < 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= SI_STEP && unitIndex < units.lastIndex) {
            value /= SI_STEP
            unitIndex++
        }
        return if (unitIndex == 0) {
            "$bytes B"
        } else {
            val tenths = (value * TENTHS_SCALE).toInt()
            val whole = tenths / TENTHS_SCALE
            val frac = tenths % TENTHS_SCALE
            if (frac == 0) {
                "$whole ${units[unitIndex]}"
            } else {
                "$whole.$frac ${units[unitIndex]}"
            }
        }
    }

    private fun bytesPerTileTable(layer: OfflineMapLayer): Map<Int, Long> = when (layer) {
        OfflineMapLayer.BASE -> BASE_BYTES_PER_TILE
        OfflineMapLayer.TOPO -> TOPO_BYTES_PER_TILE
    }

    /**
     * Web-Mercator tile count for [bounds] at zoom [z], matching iOS floor((lon+180)/360*n) x-range
     * and the tan/log y-range for NE/SW latitudes.
     */
    internal fun tileCountAtZoom(bounds: CoordinateBounds, z: Int): Long {
        if (z < 0) return 0L
        val n = (1 shl z).toDouble()
        val xMin = floor((bounds.sw.longitude + LON_OFFSET) / LON_RANGE * n).toInt()
        val xMax = floor((bounds.ne.longitude + LON_OFFSET) / LON_RANGE * n).toInt()
        val yMin = mercatorY(bounds.ne.latitude, n)
        val yMax = mercatorY(bounds.sw.latitude, n)
        val xCount = abs(xMax - xMin) + 1
        val yCount = abs(yMax - yMin) + 1
        return xCount.toLong() * yCount.toLong()
    }

    private fun mercatorY(latitudeDeg: Double, n: Double): Int {
        val lat = latitudeDeg.coerceIn(-MERCATOR_LAT_LIMIT, MERCATOR_LAT_LIMIT)
        val latRad = lat * PI / DEGREES_HALF_CIRCLE
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return floor(y).toInt()
    }

    private val BASE_BYTES_PER_TILE: Map<Int, Long> = mapOf(
        10 to 15_000L,
        11 to 25_000L,
        12 to 45_000L,
        13 to 70_000L,
        14 to 100_000L,
    )

    private val TOPO_BYTES_PER_TILE: Map<Int, Long> = mapOf(
        10 to 15_000L,
        11 to 18_000L,
        12 to 22_000L,
        13 to 25_000L,
        14 to 30_000L,
        15 to 35_000L,
        16 to 40_000L,
        17 to 45_000L,
    )

    private const val DEFAULT_BYTES_PER_TILE: Long = 10_000L
    private const val LON_OFFSET = 180.0
    private const val LON_RANGE = 360.0
    private const val DEGREES_HALF_CIRCLE = 180.0
    private const val MERCATOR_LAT_LIMIT = 85.05112878
    private const val SI_STEP = 1000.0
    private const val TENTHS_SCALE = 10
}

/**
 * Layers the user chose in the region picker (base is always included; topo is optional).
 */
fun offlineLayers(includeTopo: Boolean): Set<OfflineMapLayer> = buildSet {
    add(OfflineMapLayer.BASE)
    if (includeTopo) add(OfflineMapLayer.TOPO)
}

/**
 * Builds one [OfflineMapPack] per layer for a confirmed region download.
 */
fun buildOfflinePacks(name: String, layers: Set<OfflineMapLayer>, createdAtEpochSeconds: Long): List<OfflineMapPack> {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Region name must not be blank" }
    require(layers.isNotEmpty()) { "At least one layer is required" }
    val multi = layers.size > 1
    return layers.sortedBy { it.ordinal }.mapIndexed { index, layer ->
        val packName = if (multi) "$trimmed (${layer.rawValue})" else trimmed
        OfflineMapPack(
            id = OfflineMapPackRegistry.newPackId(packName, createdAtEpochSeconds + index),
            name = packName,
            layer = layer,
            createdAtEpochSeconds = createdAtEpochSeconds + index,
            completed = false,
            progress = 0.0,
        )
    }
}

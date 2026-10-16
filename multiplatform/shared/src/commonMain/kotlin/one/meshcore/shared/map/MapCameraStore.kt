package one.meshcore.shared.map

/**
 * Serializes a [MapCameraRegion] to and from the compact string the UI-restoration store persists.
 * [decode] is the trust boundary (spec §3.6): it rejects every value that would abort the native
 * MapLibre camera — malformed component count, invalid center, or a non-finite / non-positive span —
 * so a corrupt restoration archive can never feed the camera a process-aborting value. Mirrors iOS
 * `MapCameraStore`.
 */
object MapCameraStore {
    fun encode(region: MapCameraRegion): String = listOf(
        region.center.latitude,
        region.center.longitude,
        region.spanLatDeg,
        region.spanLonDeg,
    ).joinToString(SEPARATOR) { it.toString() }

    fun decode(value: String): MapCameraRegion? {
        val parts = value.split(SEPARATOR).mapNotNull { it.toDoubleOrNull() }
        if (parts.size != COMPONENT_COUNT) return null

        val center = LatLng(latitude = parts[LATITUDE_INDEX], longitude = parts[LONGITUDE_INDEX])
        val spanLat = parts[SPAN_LAT_INDEX]
        val spanLon = parts[SPAN_LON_INDEX]
        val valid = center.isValid &&
            spanLat.isFinite() &&
            spanLon.isFinite() &&
            spanLat > 0 &&
            spanLon > 0

        return if (valid) MapCameraRegion(center = center, spanLatDeg = spanLat, spanLonDeg = spanLon) else null
    }

    private const val SEPARATOR = ","

    /** Component count and field positions of the `lat,lon,latDelta,lonDelta` encoding. */
    private const val COMPONENT_COUNT = 4
    private const val LATITUDE_INDEX = 0
    private const val LONGITUDE_INDEX = 1
    private const val SPAN_LAT_INDEX = 2
    private const val SPAN_LON_INDEX = 3
}

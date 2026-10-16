package one.meshcore.shared.map

import kotlin.math.roundToLong

/**
 * Cache key for a chat map thumbnail. Lat/lon are rounded so nearby scroll rebuilds hit the same entry.
 * Mirrors iOS `MapSnapshotRequest` (coordinate + dark/offline style axes).
 */
data class MapSnapshotRequest(
    val latitude: Double,
    val longitude: Double,
    val isDark: Boolean,
    val isOffline: Boolean,
) {
    companion object {
        /** Five decimal places (~1.1 m), matching iOS `coordinatePrecision`. */
        private const val COORDINATE_PRECISION = 100_000.0

        fun rounded(latitude: Double, longitude: Double, isDark: Boolean, isOffline: Boolean): MapSnapshotRequest =
            MapSnapshotRequest(
                latitude = roundCoord(latitude),
                longitude = roundCoord(longitude),
                // Offline renders never use the dark style (`useDark = isDark && !isOffline`), so a dark
                // and light request would cache the same Liberty tiles twice.
                isDark = if (isOffline) false else isDark,
                isOffline = isOffline,
            )

        private fun roundCoord(value: Double): Double {
            val rounded = (value * COORDINATE_PRECISION).roundToLong().toDouble() / COORDINATE_PRECISION
            // -0.0 equals 0.0 but hashes differently, which would split one cache slot in two.
            return if (rounded == 0.0) 0.0 else rounded
        }
    }
}

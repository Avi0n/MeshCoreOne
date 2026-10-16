package one.meshcore.shared.remotenode

/**
 * A single GPS fix persisted with a snapshot. Latitude and longitude travel together so a snapshot
 * can never hold half a fix. Mirrors the Swift `NodeLocationFix`.
 */
data class NodeLocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Altitude in meters, or null when the fix carried none or an implausible one. */
    val altitude: Double? = null,
) {
    companion object {
        /**
         * Plausible altitude range in meters, roughly below the lowest dry land to above jet cruising
         * altitude. Values outside are treated as noise and dropped to null. 0 (sea level) is inside
         * the range and retained, unlike the (0,0) null-island guard on latitude/longitude.
         */
        private val ALTITUDE_RANGE = -500.0..10_000.0
        private val LATITUDE_RANGE = -90.0..90.0
        private val LONGITUDE_RANGE = -180.0..180.0

        /**
         * A fix worth plotting: geographically valid and not the (0,0) null-island sentinel a node
         * without a GPS lock frequently emits.
         */
        fun isValidFix(latitude: Double, longitude: Double): Boolean =
            latitude in LATITUDE_RANGE && longitude in LONGITUDE_RANGE && !(latitude == 0.0 && longitude == 0.0)

        /**
         * The primary fix for a telemetry reading: the first GPS data point, kept only when it resolves
         * to a plottable coordinate. A missing GPS point or a (0,0)/out-of-range fix returns null so a
         * lock-less node stores no location. Altitude rides along when present and plausible; it never
         * gates the fix.
         */
        fun primaryFix(dataPoints: List<LPPDataPoint>): NodeLocationFix? {
            val gps = dataPoints.firstNotNullOfOrNull { it.value as? LPPValue.Gps }
            if (gps == null || !isValidFix(gps.latitude, gps.longitude)) return null
            return NodeLocationFix(
                latitude = gps.latitude,
                longitude = gps.longitude,
                altitude = gps.altitude.takeIf { it in ALTITUDE_RANGE },
            )
        }
    }
}

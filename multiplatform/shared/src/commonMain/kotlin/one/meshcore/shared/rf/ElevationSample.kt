package one.meshcore.shared.rf

/** Elevation sample along the path. */
data class ElevationSample(
    val coordinate: GeoCoordinate,
    /** Elevation in meters above sea level. */
    val elevation: Double,
    val distanceFromAMeters: Double,
)

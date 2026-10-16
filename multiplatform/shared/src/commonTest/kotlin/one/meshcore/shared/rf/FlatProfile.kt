package one.meshcore.shared.rf

/** Flat samples from 37.7749, -122.4194. Both path-analysis suites assert this shape. */
internal fun flatProfile(elevation: Double, totalDistance: Double, count: Int): List<ElevationSample> =
    (0 until count).map { i ->
        val fraction = i.toDouble() / (count - 1)
        ElevationSample(
            coordinate = GeoCoordinate(37.7749 + fraction * 0.01, -122.4194),
            elevation = elevation,
            distanceFromAMeters = fraction * totalDistance,
        )
    }

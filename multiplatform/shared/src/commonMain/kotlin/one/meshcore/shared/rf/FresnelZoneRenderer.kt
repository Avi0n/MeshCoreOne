package one.meshcore.shared.rf

/**
 * Builds [ProfileSample] rows for terrain + Fresnel chart rendering. Port of Swift
 * `FresnelZoneRenderer` — segment-relative Fresnel/LOS math while preserving absolute x for layout.
 */
object FresnelZoneRenderer {
    /**
     * Linear LOS height at [atDistance] along a segment of [totalDistance] between antenna
     * heights [heightA] and [heightB] (above sea level).
     */
    fun losHeight(atDistance: Double, totalDistance: Double, heightA: Double, heightB: Double): Double {
        if (totalDistance <= 0) return heightA
        val fraction = atDistance / totalDistance
        return heightA + fraction * (heightB - heightA)
    }

    /**
     * Profile samples from an elevation series. When [elevationProfile] is a segment slice
     * (e.g. R→B), distances may start non-zero; Fresnel uses segment-relative legs while
     * [ProfileSample.x] keeps the original path coordinate.
     */
    fun buildProfileSamples(
        elevationProfile: List<ElevationSample>,
        pointAHeight: Double,
        pointBHeight: Double,
        frequencyMHz: Double,
        refractionK: Double,
    ): List<ProfileSample> {
        val first = elevationProfile.firstOrNull() ?: return emptyList()
        val last = elevationProfile.lastOrNull() ?: return emptyList()

        val segmentOffset = first.distanceFromAMeters
        val segmentLength = last.distanceFromAMeters - segmentOffset

        val heightA = first.elevation + pointAHeight
        val heightB = last.elevation + pointBHeight

        return elevationProfile.map { sample ->
            val distanceFromSegmentStart = sample.distanceFromAMeters - segmentOffset
            val distanceToSegmentEnd = segmentLength - distanceFromSegmentStart

            val yLos = losHeight(
                atDistance = distanceFromSegmentStart,
                totalDistance = segmentLength,
                heightA = heightA,
                heightB = heightB,
            )
            val radius = RFCalculator.fresnelRadius(
                frequencyMHz = frequencyMHz,
                distanceToAMeters = distanceFromSegmentStart,
                distanceToBMeters = distanceToSegmentEnd,
            )
            val earthBulge = RFCalculator.earthBulge(
                distanceToAMeters = distanceFromSegmentStart,
                distanceToBMeters = distanceToSegmentEnd,
                refractionK = refractionK,
            )
            ProfileSample(
                x = sample.distanceFromAMeters,
                yTerrain = sample.elevation + earthBulge,
                yLos = yLos,
                fresnelRadius = radius,
            )
        }
    }
}

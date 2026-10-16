package one.meshcore.shared.rf

/**
 * A filled band of the first Fresnel zone on the terrain profile chart, as a fraction of the zone radius.
 * Port of the two fills Swift `TerrainProfileCanvas` draws.
 */
enum class FresnelBand(val fraction: Double) {
    /** The full first Fresnel zone. */
    OUTER(1.0),

    /**
     * The inner 60% zone. [ProfileSample.isObstructed] and the clearance verdict are defined against this
     * edge, so the chart has to show it or the verdict rests on something the reader cannot see.
     */
    INNER(FRESNEL_INNER_FRACTION),
}

/** One vertex of a band outline, in profile coordinates: distance along the path and elevation, both meters. */
data class ProfileVertex(val x: Double, val elevation: Double)

/**
 * Closed outlines for every [FresnelBand] over [samples], outer first so the inner band fills on top.
 * Each outline runs the top edge left to right and the terrain-clamped bottom edge back again.
 */
fun fresnelBandOutlines(samples: List<ProfileSample>): List<FresnelBandOutline> {
    if (samples.size < MIN_FILL_SAMPLES) return emptyList()
    return FresnelBand.entries.map { band ->
        FresnelBandOutline(
            band = band,
            vertices = samples.map { ProfileVertex(it.x, it.yTop(band)) } +
                samples.asReversed().map { ProfileVertex(it.x, it.yVisibleBottom(band)) },
        )
    }
}

/** The closed polygon filled for [band]. */
data class FresnelBandOutline(val band: FresnelBand, val vertices: List<ProfileVertex>)

/** Upper edge of [band] at this sample. */
fun ProfileSample.yTop(band: FresnelBand): Double = yLos + fresnelRadius * band.fraction

/** Lower edge of [band], clamped into the band so the fill stops at the ground. */
fun ProfileSample.yVisibleBottom(band: FresnelBand): Double {
    val bottom = yLos - fresnelRadius * band.fraction
    return minOf(maxOf(yTerrain, bottom), yTop(band))
}

private const val MIN_FILL_SAMPLES = 2

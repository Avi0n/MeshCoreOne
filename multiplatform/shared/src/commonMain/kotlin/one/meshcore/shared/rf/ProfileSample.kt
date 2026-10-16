package one.meshcore.shared.rf

/**
 * One terrain-profile sample with LOS height and first Fresnel radius for chart rendering.
 * Port of Swift `ProfileSample`.
 */
data class ProfileSample(
    /** Distance from path start (A) in meters — absolute path coordinate for multi-segment charts. */
    val x: Double,
    /** Terrain elevation including earth bulge (m). */
    val yTerrain: Double,
    /** Line-of-sight height above sea level (m). */
    val yLos: Double,
    val fresnelRadius: Double,
) {
    val yTop: Double get() = yTop(FresnelBand.OUTER)
    val yBottom: Double get() = yLos - fresnelRadius

    /** Inner 60% zone (ideal clearance threshold). */
    val yTop60: Double get() = yTop(FresnelBand.INNER)
    val yBottom60: Double get() = yLos - fresnelRadius * FRESNEL_INNER_FRACTION

    val yVisibleBottom60: Double get() = yVisibleBottom(FresnelBand.INNER)

    /** Terrain intrudes past the 60% Fresnel clearance threshold. */
    val isObstructed: Boolean get() = yTerrain > yBottom60

    val yVisibleBottom: Double get() = yVisibleBottom(FresnelBand.OUTER)
}

internal const val FRESNEL_INNER_FRACTION = 0.6

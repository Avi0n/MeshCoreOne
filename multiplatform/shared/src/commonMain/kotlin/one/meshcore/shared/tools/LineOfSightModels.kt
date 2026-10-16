package one.meshcore.shared.tools

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.ElevationServiceError
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.rf.ElevationSample
import one.meshcore.shared.rf.PathAnalysisResult
import one.meshcore.shared.rf.ProfileSample
import one.meshcore.shared.rf.RelayPathAnalysisResult

/** Which endpoint (or repeater) is being edited / relocated. */
enum class LosPointId {
    POINT_A,
    POINT_B,
    REPEATER,
}

/**
 * A selected endpoint for LOS analysis. [contact] is set when chosen from a map pin;
 * dropped pins have null contact. Port of Swift `SelectedPoint`.
 */
data class LosSelectedPoint(
    val coordinate: LatLng,
    val contact: ContactDto? = null,
    val groundElevation: Double? = null,
    val additionalHeight: Double = DEFAULT_POINT_ADDITIONAL_HEIGHT_M,
) {
    val totalHeight: Double?
        get() = groundElevation?.let { it + additionalHeight }

    val isLoadingElevation: Boolean
        get() = groundElevation == null

    fun displayName(droppedPinLabel: String): String = contact?.displayName ?: droppedPinLabel
}

/**
 * On-path or off-path relay node. Path fraction is only meaningful when [isOnPath].
 * Port of Swift `RepeaterPoint`. Callers should pass a clamped [pathFraction].
 */
data class LosRepeaterPoint(
    val coordinate: LatLng,
    val groundElevation: Double? = null,
    val additionalHeight: Double = DEFAULT_REPEATER_ADDITIONAL_HEIGHT_M,
    val isOnPath: Boolean = true,
    val pathFraction: Double = DEFAULT_REPEATER_PATH_FRACTION,
)

/** Analysis pipeline status shown in the sheet. */
sealed class LosAnalysisStatus {
    data object Idle : LosAnalysisStatus()
    data class Result(val value: PathAnalysisResult) : LosAnalysisStatus()
    data class RelayResult(val value: RelayPathAnalysisResult) : LosAnalysisStatus()
    data class Error(val failure: LosAnalysisFailure) : LosAnalysisStatus()
}

/**
 * Why an analysis produced no clearance verdict. Typed rather than a raw message because the text is
 * user-facing and resolves to a localized resource at render time; [Unknown] carries a lower layer's
 * message verbatim.
 */
sealed interface LosAnalysisFailure {
    data class NetworkError(val reason: String) : LosAnalysisFailure

    data object InvalidResponse : LosAnalysisFailure

    data class ApiError(val detail: String) : LosAnalysisFailure

    data object NoData : LosAnalysisFailure

    data object RateLimited : LosAnalysisFailure

    data class Unknown(val detail: String) : LosAnalysisFailure
}

/** Maps a terrain-fetch failure onto the surfaced [LosAnalysisFailure]. */
internal fun losAnalysisFailure(error: Throwable): LosAnalysisFailure = when (error) {
    is ElevationServiceError.NetworkError -> LosAnalysisFailure.NetworkError(error.reason)
    is ElevationServiceError.ApiError -> LosAnalysisFailure.ApiError(error.detail)
    ElevationServiceError.InvalidResponse -> LosAnalysisFailure.InvalidResponse
    ElevationServiceError.NoData -> LosAnalysisFailure.NoData
    ElevationServiceError.RateLimited -> LosAnalysisFailure.RateLimited
    else -> LosAnalysisFailure.Unknown(error.message ?: error.toString())
}

const val DEFAULT_POINT_ADDITIONAL_HEIGHT_M = 7.0
const val DEFAULT_REPEATER_ADDITIONAL_HEIGHT_M = 10.0
const val DEFAULT_REPEATER_PATH_FRACTION = 0.5

const val PATH_FRACTION_MIN = 0.05
const val PATH_FRACTION_MAX = 0.95

/**
 * Maps a horizontal canvas coordinate to the on-path repeater fraction iOS clamps during
 * `TerrainProfileCanvas` drag (`0.05...0.95`).
 */
fun pathFractionFromChartX(x: Float, width: Float): Double {
    if (width <= 0f) return PATH_FRACTION_MIN
    return (x / width).toDouble().coerceIn(PATH_FRACTION_MIN, PATH_FRACTION_MAX)
}

/**
 * Linear sample at [fraction] along [samples], matching iOS interpolating
 * `pathFraction * (elevationProfile.count - 1)` for the repeater marker.
 */
fun interpolateProfileAtFraction(samples: List<ProfileSample>, fraction: Double): ProfileSample? {
    if (samples.size < 2) return null
    val clamped = fraction.coerceIn(0.0, 1.0)
    val index = clamped * samples.lastIndex
    val lo = index.toInt().coerceIn(0, samples.lastIndex - 1)
    val hi = lo + 1
    val t = index - lo
    fun lerp(a: Double, b: Double) = a + (b - a) * t
    val start = samples[lo]
    val end = samples[hi]
    return ProfileSample(
        x = lerp(start.x, end.x),
        yTerrain = lerp(start.yTerrain, end.yTerrain),
        yLos = lerp(start.yLos, end.yLos),
        fresnelRadius = lerp(start.fresnelRadius, end.fresnelRadius),
    )
}

/**
 * Chart X for the repeater marker: on-path uses the drag fraction; off-path is A→R / A→B via R,
 * matching iOS `repeaterVisualizationPathFraction`.
 */
fun repeaterVisualizationPathFraction(
    repeater: LosRepeaterPoint?,
    elevationProfileAr: List<ElevationSample>,
    elevationProfileRb: List<ElevationSample>,
): Double? {
    if (repeater == null) return null
    if (repeater.isOnPath) return repeater.pathFraction
    val arLast = elevationProfileAr.lastOrNull() ?: return null
    val rbLast = elevationProfileRb.lastOrNull() ?: return null
    if (rbLast.distanceFromAMeters <= 0.0) return null
    return arLast.distanceFromAMeters / rbLast.distanceFromAMeters
}

/** First and last profile samples for the A and B endpoint markers. */
fun terrainProfileEndpointSamples(samples: List<ProfileSample>): Pair<ProfileSample, ProfileSample>? {
    if (samples.size < 2) return null
    return samples.first() to samples.last()
}

/**
 * A→R length for the off-path junction dashed line. Nil on-path, matching iOS
 * `segmentARDistanceMeters`.
 */
fun offPathSegmentArDistanceMeters(repeater: LosRepeaterPoint?, analysisStatus: LosAnalysisStatus): Double? {
    if (repeater == null || repeater.isOnPath) return null
    val result = analysisStatus as? LosAnalysisStatus.RelayResult ?: return null
    return result.value.segmentAR.distanceMeters
}

const val DEFAULT_FREQUENCY_MHZ = 906.0
const val DEFAULT_REFRACTION_K = 1.0

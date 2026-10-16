package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.rf.ElevationSample
import one.meshcore.shared.rf.ProfileSample
import kotlin.math.abs

/**
 * Map-first Line of Sight tool state: points A/B, optional repeater, elevation fetch, RF analysis,
 * and map geometry. Port of Swift `LineOfSightViewModel` without SwiftUI bindings.
 *
 * Mutable UI state is plain properties; the Compose screen observes a [revision] counter so
 * recomposition stays simple and the VM stays unit-testable on desktop JVM.
 */
class LineOfSightViewModel(
    internal val scope: CoroutineScope,
    internal val elevationService: ElevationService? = null,
    internal val fetchContacts: suspend (Uuid) -> List<ContactDto> = { emptyList() },
) {
    val pointAMapId: String = "los-point-a"
    val pointBMapId: String = "los-point-b"
    val repeaterTargetMapId: String = "los-repeater-target"

    var pointA: LosSelectedPoint? = null
        internal set
    var pointB: LosSelectedPoint? = null
        internal set
    var repeaterPoint: LosRepeaterPoint? = null
        internal set
    var relocatingPoint: LosPointId? = null
        set(value) {
            field = value
            rebuildMapLines()
            bump()
        }

    var frequencyMHz: Double = DEFAULT_FREQUENCY_MHZ
        internal set
    var refractionK: Double = DEFAULT_REFRACTION_K
        internal set

    var showLabels: Boolean = true
        set(value) {
            field = value
            rebuildMapPoints()
            bump()
        }

    var mapPoints: List<MapPoint> = emptyList()
        internal set
    var mapLines: List<MapLine> = emptyList()
        internal set
    var cameraRegion: MapCameraRegion? = null
        internal set
    var cameraTargetVersion: Int = 0
        internal set
    internal var hasUserAdjustedCamera = false
    internal var hasAppliedInitialCameraFit = false
    internal var hasSeededFrequency = false
    internal var repeaterCatalogRadioId: Uuid? = null
    internal var repeaterLoadGeneration = 0L
    var cameraTarget: MapCameraRegion? = null
        internal set
    var shouldAutoZoomOnNextResult: Boolean = false

    var repeatersWithLocation: List<ContactDto> = emptyList()
        internal set

    var analysisStatus: LosAnalysisStatus = LosAnalysisStatus.Idle
        internal set
    var isAnalyzing: Boolean = false
        internal set
    var elevationProfile: List<ElevationSample> = emptyList()
        internal set
    var profileSamples: List<ProfileSample> = emptyList()
        internal set
    var profileSamplesRb: List<ProfileSample> = emptyList()
        internal set
    var elevationProfileAr: List<ElevationSample> = emptyList()
        internal set
    var elevationProfileRb: List<ElevationSample> = emptyList()
        internal set
    var elevationFetchFailed: Boolean = false
        internal set

    /** Bumps on every state change so Compose can collect and recompose. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    internal var analysisJob: Job? = null
    internal var pointAElevationJob: Job? = null
    internal var pointBElevationJob: Job? = null
    internal var repeaterElevationJob: Job? = null
    internal var repeaterLoadJob: Job? = null
    internal var radioIdProvider: () -> Uuid? = { null }

    /** Stops elevation and analysis work when the model owner is retired. */
    fun close() {
        repeaterLoadGeneration++
        analysisJob?.cancel()
        pointAElevationJob?.cancel()
        pointBElevationJob?.cancel()
        repeaterElevationJob?.cancel()
        repeaterLoadJob?.cancel()
        analysisJob = null
        pointAElevationJob = null
        pointBElevationJob = null
        repeaterElevationJob = null
        repeaterLoadJob = null
    }

    val canAnalyze: Boolean
        get() = pointA?.groundElevation != null && pointB?.groundElevation != null

    val shouldShowRepeaterPlaceholder: Boolean
        get() {
            val result = (analysisStatus as? LosAnalysisStatus.Result)?.value ?: return false
            return result.clearanceStatus != one.meshcore.shared.rf.ClearanceStatus.Clear &&
                result.obstructionPoints.isNotEmpty()
        }

    val shouldShowRepeaterRow: Boolean
        get() = repeaterPoint != null || shouldShowRepeaterPlaceholder

    /** Elevation profile for the terrain chart (concatenated when repeater is off-path). */
    val terrainElevationProfile: List<ElevationSample>
        get() {
            val repeater = repeaterPoint
            if (repeater == null || repeater.isOnPath) return elevationProfile
            if (elevationProfileAr.isEmpty() || elevationProfileRb.isEmpty()) return elevationProfile
            return elevationProfileAr + elevationProfileRb.drop(1)
        }

    /**
     * Samples for the terrain chart: both relay legs concatenated whenever a second leg exists. Their
     * distances are already absolute from A, so the join is contiguous — and gating this on the repeater
     * being off-path drew only the A→R half while the card reported a verdict for both.
     */
    val terrainProfileSamples: List<ProfileSample>
        get() {
            if (profileSamples.isEmpty() || profileSamplesRb.isEmpty()) return profileSamples
            return profileSamples + profileSamplesRb.drop(1)
        }

    internal data class Interpolation(val lower: Int, val upper: Int, val t: Double)

    internal fun interpolationIndices(pathFraction: Double): Interpolation? {
        if (elevationProfile.size < 2) return null
        val clamped = pathFraction.coerceIn(0.0, 1.0)
        val index = clamped * (elevationProfile.size - 1)
        val lower = index.toInt()
        val upper = minOf(lower + 1, elevationProfile.size - 1)
        val t = index - lower
        return Interpolation(lower, upper, t)
    }

    internal fun bump() {
        _revision.update { it + 1 }
    }

    companion object {
        internal const val KHZ_PER_MHZ = 1000.0
        internal const val SEA_LEVEL_ELEVATION_M = 0.0
        internal const val LINE_ACTIVE_OPACITY = 0.7
        internal const val LINE_DIM_OPACITY = 0.3
        internal const val COORD_EPSILON = 1e-9

        internal fun sameCoordinate(a: LatLng, b: LatLng): Boolean = abs(a.latitude - b.latitude) < COORD_EPSILON &&
            abs(a.longitude - b.longitude) < COORD_EPSILON
    }
}

package one.meshcore.shared.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.ElevationServiceError
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.rf.ElevationSample
import one.meshcore.shared.rf.GeoCoordinate
import one.meshcore.shared.rf.RFCalculator
import one.meshcore.shared.rf.terrainProfileFromMapSamples
import one.meshcore.shared.runExceptionFallback

fun LineOfSightViewModel.configure(radioId: () -> Uuid?, deviceFrequencyKHz: Long? = null) {
    radioIdProvider = radioId
    seedDeviceFrequency(deviceFrequencyKHz)
}

internal fun LineOfSightViewModel.seedDeviceFrequency(deviceFrequencyKHz: Long?) {
    if (!hasSeededFrequency && deviceFrequencyKHz != null && deviceFrequencyKHz > 0) {
        setFrequencyMHz(deviceFrequencyKHz.toDouble() / LineOfSightViewModel.KHZ_PER_MHZ)
    }
}

internal suspend fun LineOfSightViewModel.fetchElevationForPointA() {
    fetchPointElevation(read = { pointA }, write = { pointA = it })
}

internal suspend fun LineOfSightViewModel.fetchElevationForPointB() {
    fetchPointElevation(read = { pointB }, write = { pointB = it })
}

/**
 * Reads the point, fetches, then writes only when that point is still the coordinate that was fetched.
 * A move during the fetch keeps the newer point.
 */
private suspend fun LineOfSightViewModel.fetchPointElevation(
    read: () -> LosSelectedPoint?,
    write: (LosSelectedPoint) -> Unit,
) {
    val coordinate = read()?.coordinate ?: return
    val elev = fetchElevationOrSeaLevel(coordinate)
    currentCoroutineContext().ensureActive()
    val current = read() ?: return
    if (!LineOfSightViewModel.sameCoordinate(current.coordinate, coordinate)) return
    write(current.copy(groundElevation = elev))
    bump()
}

/** One coordinate's elevation, or null when it cannot be fetched. Never stands in a value. */
internal suspend fun LineOfSightViewModel.fetchElevationOrNull(coordinate: LatLng): Double? {
    val service = elevationService ?: return null
    val caller = currentCoroutineContext()
    val elevation = runExceptionFallback({
        caller.ensureActive()
        null
    }) {
        service.fetchElevation(coordinate)
    }
    currentCoroutineContext().ensureActive()
    return elevation
}

/**
 * An endpoint's elevation, falling back to sea level so the user can still analyze with a stated
 * approximation. Only a single endpoint is approximated here; a whole terrain profile is not, because
 * flat terrain obstructs nothing and would turn a blocked path into a clear verdict.
 */
internal suspend fun LineOfSightViewModel.fetchElevationOrSeaLevel(coordinate: LatLng): Double {
    val elevation = fetchElevationOrNull(coordinate)
    currentCoroutineContext().ensureActive()
    if (elevation != null) return elevation
    elevationFetchFailed = true
    return LineOfSightViewModel.SEA_LEVEL_ELEVATION_M
}

/** Throws rather than fabricating: the caller turns a terrain failure into a no-verdict error state. */
internal suspend fun LineOfSightViewModel.fetchPathProfile(from: LatLng, to: LatLng): List<ElevationSample> {
    val service = elevationService ?: throw ElevationServiceError.NoData
    val distance = RFCalculator.distance(
        GeoCoordinate(from.latitude, from.longitude),
        GeoCoordinate(to.latitude, to.longitude),
    )
    val sampleCount = ElevationService.optimalSampleCount(distance)
    val path = ElevationService.sampleCoordinates(from, to, sampleCount)
    val elevations = service.fetchElevations(path)
    currentCoroutineContext().ensureActive()
    return terrainProfileFromMapSamples(elevations)
}

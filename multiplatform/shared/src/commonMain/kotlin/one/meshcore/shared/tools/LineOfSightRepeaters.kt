package one.meshcore.shared.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.boundingRegion
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.runReportingException

fun LineOfSightViewModel.loadRepeaters() {
    val generation = ++repeaterLoadGeneration
    repeaterLoadJob?.cancel()
    repeaterLoadJob = null
    val radio = radioIdProvider() ?: return
    if (repeaterCatalogRadioId != null && repeaterCatalogRadioId != radio) {
        repeatersWithLocation = emptyList()
        rebuildMapPoints()
        bump()
    }
    repeaterCatalogRadioId = radio
    repeaterLoadJob = scope.launch {
        val caller = currentCoroutineContext()
        val contacts = runReportingException(onFailure = {
            caller.ensureActive()
            null
        }) {
            fetchContacts(radio)
        } ?: return@launch
        currentCoroutineContext().ensureActive()
        if (generation != repeaterLoadGeneration || radioIdProvider() != radio) return@launch
        repeatersWithLocation = contacts.filter { it.hasLocation && it.contactType == ContactType.REPEATER }
        rebuildMapPoints()
        applyInitialRepeaterCameraFitIfNeeded()
        bump()
    }
}

/**
 * Places a repeater at the worst obstruction and re-analyzes. Every repeater mutation below
 * re-analyzes for the same reason: leaving the direct-path verdict on screen beside a relay the user
 * can see reports a clearance for a path that is not the one being shown, and a position or height
 * change can leave that stale verdict optimistic.
 */
fun LineOfSightViewModel.addRepeater() {
    val result = (analysisStatus as? LosAnalysisStatus.Result)?.value ?: return
    val worst = result.worstObstructionPoint ?: return
    if (result.distanceMeters <= 0) return
    val pathFraction = (worst.distanceFromAMeters / result.distanceMeters)
        .coerceIn(PATH_FRACTION_MIN, PATH_FRACTION_MAX)
    val coordinate = coordinateAt(pathFraction) ?: return
    val elevation = elevationAt(pathFraction)
    repeaterPoint = LosRepeaterPoint(
        coordinate = coordinate,
        groundElevation = elevation,
        additionalHeight = DEFAULT_REPEATER_ADDITIONAL_HEIGHT_M,
        isOnPath = true,
        pathFraction = pathFraction,
    )
    rebuildMapPoints()
    rebuildMapLines()
    bump()
    analyzeWithRepeater()
}

fun LineOfSightViewModel.updateRepeaterPosition(pathFraction: Double) {
    val current = repeaterPoint ?: return
    if (!current.isOnPath) return
    val clamped = pathFraction.coerceIn(PATH_FRACTION_MIN, PATH_FRACTION_MAX)
    val coordinate = coordinateAt(clamped) ?: current.coordinate
    val elevation = elevationAt(clamped)
    repeaterPoint = current.copy(
        coordinate = coordinate,
        groundElevation = elevation,
        pathFraction = clamped,
    )
    rebuildMapPoints()
    rebuildMapLines()
    bump()
    analyzeWithRepeater()
}

fun LineOfSightViewModel.updateRepeaterHeight(meters: Double) {
    val current = repeaterPoint ?: return
    repeaterPoint = current.copy(additionalHeight = clampAdditionalHeightMeters(meters))
    bump()
    analyzeWithRepeater()
}

fun LineOfSightViewModel.setRepeaterOffPath(coordinate: LatLng) {
    val existingHeight = repeaterPoint?.additionalHeight ?: DEFAULT_REPEATER_ADDITIONAL_HEIGHT_M
    repeaterPoint = LosRepeaterPoint(
        coordinate = coordinate,
        groundElevation = null,
        additionalHeight = existingHeight,
        isOnPath = false,
        pathFraction = DEFAULT_REPEATER_PATH_FRACTION,
    )
    elevationProfileAr = emptyList()
    elevationProfileRb = emptyList()
    rebuildMapPoints()
    rebuildMapLines()
    bump()
    repeaterElevationJob?.cancel()
    repeaterElevationJob = scope.launch {
        // A repeater whose elevation never arrived keeps its loading row rather than claiming sea level.
        val elev = fetchElevationOrNull(coordinate) ?: return@launch
        val r = repeaterPoint ?: return@launch
        if (!LineOfSightViewModel.sameCoordinate(r.coordinate, coordinate)) return@launch
        repeaterPoint = r.copy(groundElevation = elev)
        bump()
    }
}

fun LineOfSightViewModel.clearRepeater() {
    repeaterElevationJob?.cancel()
    repeaterPoint = null
    reanalyzeWithCachedProfileIfNeeded()
    rebuildMapPoints()
    rebuildMapLines()
    bump()
}

fun LineOfSightViewModel.centerOnAllRepeaters() {
    val coords = repeatersWithLocation.map { LatLng(it.latitude, it.longitude) }
    coords.boundingRegion()?.let { setCameraRegion(it) }
}

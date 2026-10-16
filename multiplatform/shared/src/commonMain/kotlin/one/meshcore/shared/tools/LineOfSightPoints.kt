package one.meshcore.shared.tools

import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng

fun LineOfSightViewModel.selectPoint(at: LatLng, from: ContactDto? = null) {
    when {
        pointA == null -> setPointA(at, from)
        pointB == null -> setPointB(at, from)
        else -> setPointB(at, from)
    }
}

fun LineOfSightViewModel.setPointA(coordinate: LatLng, contact: ContactDto? = null) {
    pointAElevationJob?.cancel()
    invalidateAnalysis()
    pointA = LosSelectedPoint(coordinate = coordinate, contact = contact, groundElevation = null)
    rebuildMapPoints()
    rebuildMapLines()
    bump()
    pointAElevationJob = scope.launch { fetchElevationForPointA() }
}

fun LineOfSightViewModel.setPointB(coordinate: LatLng, contact: ContactDto? = null) {
    val a = pointA
    if (a != null && LineOfSightViewModel.sameCoordinate(a.coordinate, coordinate)) return
    pointBElevationJob?.cancel()
    invalidateAnalysis()
    pointB = LosSelectedPoint(coordinate = coordinate, contact = contact, groundElevation = null)
    rebuildMapPoints()
    rebuildMapLines()
    bump()
    pointBElevationJob = scope.launch { fetchElevationForPointB() }
}

fun LineOfSightViewModel.clearPointA() {
    pointAElevationJob?.cancel()
    pointA = null
    repeaterPoint = null
    invalidateAnalysis()
    rebuildMapPoints()
    rebuildMapLines()
    bump()
}

fun LineOfSightViewModel.clearPointB() {
    pointBElevationJob?.cancel()
    pointB = null
    repeaterPoint = null
    invalidateAnalysis()
    rebuildMapPoints()
    rebuildMapLines()
    bump()
}

fun LineOfSightViewModel.clear() {
    pointAElevationJob?.cancel()
    pointBElevationJob?.cancel()
    analysisJob?.cancel()
    repeaterElevationJob?.cancel()
    pointA = null
    pointB = null
    repeaterPoint = null
    elevationFetchFailed = false
    isAnalyzing = false
    analysisStatus = LosAnalysisStatus.Idle
    elevationProfile = emptyList()
    elevationProfileAr = emptyList()
    elevationProfileRb = emptyList()
    profileSamples = emptyList()
    profileSamplesRb = emptyList()
    rebuildMapPoints()
    rebuildMapLines()
    bump()
}

fun LineOfSightViewModel.updateAdditionalHeight(forPoint: LosPointId, meters: Double) {
    val clamped = clampAdditionalHeightMeters(meters)
    when (forPoint) {
        LosPointId.POINT_A -> {
            val p = pointA ?: return
            pointA = p.copy(additionalHeight = clamped)
            invalidateAnalysis()
        }
        LosPointId.POINT_B -> {
            val p = pointB ?: return
            pointB = p.copy(additionalHeight = clamped)
            invalidateAnalysis()
        }
        LosPointId.REPEATER -> updateRepeaterHeight(clamped)
    }
    rebuildMapPoints()
    bump()
}

fun LineOfSightViewModel.setFrequencyMHz(value: Double) {
    if (!value.isFinite() || value <= 0) return
    hasSeededFrequency = true
    if (value == frequencyMHz) return
    frequencyMHz = value
    reanalyzeWithCachedProfileIfNeeded()
    bump()
}

fun LineOfSightViewModel.setRefractionK(value: Double) {
    if (value <= 0 || value == refractionK) return
    refractionK = value
    reanalyzeWithCachedProfileIfNeeded()
    bump()
}

fun LineOfSightViewModel.toggleContact(contact: ContactDto) {
    val coordinate = LatLng(contact.latitude, contact.longitude)
    if (pointA?.contact?.id == contact.id) {
        clearPointA()
        return
    }
    if (pointB?.contact?.id == contact.id) {
        clearPointB()
        return
    }
    selectPoint(at = coordinate, from = contact)
}

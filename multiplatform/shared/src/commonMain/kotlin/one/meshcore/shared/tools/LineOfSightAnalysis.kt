package one.meshcore.shared.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import one.meshcore.shared.rf.FresnelZoneRenderer
import one.meshcore.shared.rf.RFCalculator
import one.meshcore.shared.rf.RelayPathAnalysisResult
import one.meshcore.shared.runReportingException

fun LineOfSightViewModel.clearAnalysisResults() {
    analysisJob?.cancel()
    analysisJob = null
    isAnalyzing = false
    analysisStatus = LosAnalysisStatus.Idle
    shouldAutoZoomOnNextResult = false
    bump()
}

fun LineOfSightViewModel.analyze() {
    val a = pointA
    val b = pointB
    if (a?.groundElevation == null || b?.groundElevation == null) return

    analysisJob?.cancel()
    isAnalyzing = true
    bump()

    val pointACoord = a.coordinate
    val pointBCoord = b.coordinate
    val heightA = a.additionalHeight
    val heightB = b.additionalHeight
    val freq = frequencyMHz
    val k = refractionK

    analysisJob = scope.launch {
        val caller = currentCoroutineContext()
        runReportingException<Unit>(onFailure = { error ->
            caller.ensureActive()
            failAnalysis(error)
        }) {
            val profile = fetchPathProfile(pointACoord, pointBCoord)
            val result = RFCalculator.analyzePath(
                elevationProfile = profile,
                pointAHeightMeters = heightA,
                pointBHeightMeters = heightB,
                frequencyMHz = freq,
                refractionK = k,
            )
            elevationProfile = profile
            profileSamples = FresnelZoneRenderer.buildProfileSamples(
                elevationProfile = profile,
                pointAHeight = heightA,
                pointBHeight = heightB,
                frequencyMHz = freq,
                refractionK = k,
            )
            profileSamplesRb = emptyList()
            isAnalyzing = false
            analysisStatus = LosAnalysisStatus.Result(result)
            rebuildMapPoints()
            rebuildMapLines()
            if (shouldAutoZoomOnNextResult) {
                shouldAutoZoomOnNextResult = false
                zoomToShowBothPoints()
            }
            bump()
        }
    }
}

fun LineOfSightViewModel.analyzeWithRepeater() {
    val repeater = repeaterPoint ?: return
    val a = pointA ?: return
    val b = pointB ?: return
    if (repeater.isOnPath) {
        analyzeWithRepeaterOnPath()
    } else if (elevationProfileAr.isNotEmpty() && elevationProfileRb.isNotEmpty()) {
        applyRelayAnalysis(
            RelayAnalysisRequest(
                profileAr = elevationProfileAr,
                profileRb = elevationProfileRb,
                pointAHeight = a.additionalHeight,
                repeaterHeight = repeater.additionalHeight,
                pointBHeight = b.additionalHeight,
                frequencyMHz = frequencyMHz,
                refractionK = refractionK,
            ),
        )
        bump()
    } else {
        analysisJob?.cancel()
        analysisJob = scope.launch { analyzeWithRepeaterOffPath() }
    }
}

/**
 * Ends an analysis with an error and no verdict. The cached profile goes with it: an analysis that
 * never got terrain must not leave one behind for a later re-analysis to answer from.
 */
internal fun LineOfSightViewModel.failAnalysis(error: Throwable) {
    isAnalyzing = false
    elevationProfile = emptyList()
    elevationProfileAr = emptyList()
    elevationProfileRb = emptyList()
    profileSamples = emptyList()
    profileSamplesRb = emptyList()
    analysisStatus = LosAnalysisStatus.Error(losAnalysisFailure(error))
    rebuildMapPoints()
    rebuildMapLines()
    bump()
}

internal fun LineOfSightViewModel.analyzeWithRepeaterOnPath() {
    val repeater = repeaterPoint ?: return
    val a = pointA ?: return
    val b = pointB ?: return
    if (elevationProfile.size < 2) return
    val splitIndex = (repeater.pathFraction * (elevationProfile.size - 1)).toInt()
    if (splitIndex <= 0 || splitIndex >= elevationProfile.size - 1) return
    applyRelayAnalysis(
        RelayAnalysisRequest(
            profileAr = elevationProfile.subList(0, splitIndex + 1),
            profileRb = elevationProfile.subList(splitIndex, elevationProfile.size),
            pointAHeight = a.additionalHeight,
            repeaterHeight = repeater.additionalHeight,
            pointBHeight = b.additionalHeight,
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
        ),
    )
    bump()
}

internal suspend fun LineOfSightViewModel.analyzeWithRepeaterOffPath() {
    val repeater = repeaterPoint ?: return
    val a = pointA ?: return
    val b = pointB ?: return
    isAnalyzing = true
    bump()
    val caller = currentCoroutineContext()
    runReportingException<Unit>(onFailure = { error ->
        caller.ensureActive()
        failAnalysis(error)
    }) {
        val profileAr = fetchPathProfile(a.coordinate, repeater.coordinate)
        val profileRbRaw = fetchPathProfile(repeater.coordinate, b.coordinate)
        val distanceAr = profileAr.lastOrNull()?.distanceFromAMeters ?: 0.0
        val profileRb = profileRbRaw.map { sample ->
            sample.copy(distanceFromAMeters = sample.distanceFromAMeters + distanceAr)
        }
        elevationProfileAr = profileAr
        elevationProfileRb = profileRb
        applyRelayAnalysis(
            RelayAnalysisRequest(
                profileAr = profileAr,
                profileRb = profileRb,
                pointAHeight = a.additionalHeight,
                repeaterHeight = repeater.additionalHeight,
                pointBHeight = b.additionalHeight,
                frequencyMHz = frequencyMHz,
                refractionK = refractionK,
            ),
        )
        isAnalyzing = false
        bump()
    }
}

internal fun LineOfSightViewModel.applyRelayAnalysis(request: RelayAnalysisRequest) {
    val toRepeater = request.relaySegment(RelayLeg.TO_REPEATER)
    val fromRepeater = request.relaySegment(RelayLeg.FROM_REPEATER)
    val relay = RelayPathAnalysisResult(
        segmentAR = toRepeater.analysis,
        segmentRB = fromRepeater.analysis,
    )
    profileSamples = toRepeater.samples
    profileSamplesRb = fromRepeater.samples
    analysisStatus = LosAnalysisStatus.RelayResult(relay)
    rebuildMapPoints()
    rebuildMapLines()
}

internal fun LineOfSightViewModel.invalidateAnalysisOnly() {
    analysisJob?.cancel()
    isAnalyzing = false
    analysisStatus = LosAnalysisStatus.Idle
    shouldAutoZoomOnNextResult = false
}

internal fun LineOfSightViewModel.invalidateAnalysis() {
    invalidateAnalysisOnly()
    repeaterElevationJob?.cancel()
    elevationProfile = emptyList()
    elevationProfileAr = emptyList()
    elevationProfileRb = emptyList()
    profileSamples = emptyList()
    profileSamplesRb = emptyList()
    elevationFetchFailed = false
    repeaterPoint = null
}

internal fun LineOfSightViewModel.reanalyzeWithCachedProfileIfNeeded() {
    analysisJob?.cancel()
    analysisJob = null
    val cachedA = pointA?.takeIf { it.groundElevation != null }
    val cachedB = pointB?.takeIf { it.groundElevation != null }
    if (elevationProfile.isEmpty() || cachedA == null || cachedB == null) {
        isAnalyzing = false
        return
    }
    if (repeaterPoint != null) {
        isAnalyzing = false
        analyzeWithRepeater()
        return
    }
    isAnalyzing = true
    val profile = elevationProfile
    val heightA = cachedA.additionalHeight
    val heightB = cachedB.additionalHeight
    val freq = frequencyMHz
    val k = refractionK
    analysisJob = scope.launch {
        val result = RFCalculator.analyzePath(
            elevationProfile = profile,
            pointAHeightMeters = heightA,
            pointBHeightMeters = heightB,
            frequencyMHz = freq,
            refractionK = k,
        )
        profileSamples = FresnelZoneRenderer.buildProfileSamples(
            elevationProfile = profile,
            pointAHeight = heightA,
            pointBHeight = heightB,
            frequencyMHz = freq,
            refractionK = k,
        )
        profileSamplesRb = emptyList()
        isAnalyzing = false
        analysisStatus = LosAnalysisStatus.Result(result)
        rebuildMapPoints()
        rebuildMapLines()
        bump()
    }
}

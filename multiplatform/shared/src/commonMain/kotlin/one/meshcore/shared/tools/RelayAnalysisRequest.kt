package one.meshcore.shared.tools

import one.meshcore.shared.rf.ElevationSample
import one.meshcore.shared.rf.FresnelZoneRenderer
import one.meshcore.shared.rf.ProfileSample
import one.meshcore.shared.rf.RFCalculator
import one.meshcore.shared.rf.SegmentAnalysisResult

internal data class RelayAnalysisRequest(
    val profileAr: List<ElevationSample>,
    val profileRb: List<ElevationSample>,
    val pointAHeight: Double,
    val repeaterHeight: Double,
    val pointBHeight: Double,
    val frequencyMHz: Double,
    val refractionK: Double,
)

/** A repeater hop. [TO_REPEATER] is A to the repeater. [FROM_REPEATER] is the repeater to B. */
internal enum class RelayLeg {
    TO_REPEATER,
    FROM_REPEATER,
}

internal data class RelaySegment(val analysis: SegmentAnalysisResult, val samples: List<ProfileSample>)

/** Analyzes one repeater hop and builds its chart samples. */
internal fun RelayAnalysisRequest.relaySegment(leg: RelayLeg): RelaySegment {
    val toRepeater = leg == RelayLeg.TO_REPEATER
    val profile = if (toRepeater) profileAr else profileRb
    val startHeight = if (toRepeater) pointAHeight else repeaterHeight
    val endHeight = if (toRepeater) repeaterHeight else pointBHeight
    val result = RFCalculator.analyzePathSegment(
        elevationProfile = profile,
        startHeightMeters = startHeight,
        endHeightMeters = endHeight,
        frequencyMHz = frequencyMHz,
        refractionK = refractionK,
    )
    return RelaySegment(
        analysis = SegmentAnalysisResult(
            startLabel = if (toRepeater) "A" else "R",
            endLabel = if (toRepeater) "R" else "B",
            clearanceStatus = result.clearanceStatus,
            distanceMeters = result.distanceMeters,
            worstClearancePercent = result.worstClearancePercent,
        ),
        samples = FresnelZoneRenderer.buildProfileSamples(
            elevationProfile = profile,
            pointAHeight = startHeight,
            pointBHeight = endHeight,
            frequencyMHz = frequencyMHz,
            refractionK = refractionK,
        ),
    )
}

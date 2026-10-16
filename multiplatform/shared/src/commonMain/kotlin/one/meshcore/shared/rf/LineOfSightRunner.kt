package one.meshcore.shared.rf

import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.map.ElevationServiceError
import one.meshcore.shared.map.LatLng
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs LOS analysis, preferring Open-Meteo terrain when [elevationService] is present. On any
 * elevation failure (network, rate limit, empty), falls back to the flat sea-level profile so the
 * user still gets RF metrics.
 */
suspend fun runLineOfSightAnalysis(
    inputs: LineOfSightInputs,
    elevationService: ElevationService? = null,
): LineOfSightRun {
    if (elevationService == null) return runLineOfSightFlat(inputs)

    return try {
        val from = LatLng(inputs.pointA.latitude, inputs.pointA.longitude)
        val to = LatLng(inputs.pointB.latitude, inputs.pointB.longitude)
        val distance = RFCalculator.distance(inputs.pointA, inputs.pointB)
        val path = ElevationService.sampleCoordinates(
            from,
            to,
            ElevationService.optimalSampleCount(distance),
        )
        val mapSamples = elevationService.fetchElevations(path)
        val profile = terrainProfileFromMapSamples(mapSamples)
        LineOfSightRun(
            analysis = analyzeLineOfSightWithProfile(inputs, profile),
            profile = profile,
            usedTerrainElevation = true,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (_: ElevationServiceError) {
        runLineOfSightFlat(inputs)
    }
}

data class LineOfSightShareLabels(
    val title: String,
    val distance: String,
    val clearance: String,
    val freeSpaceLoss: String,
    val diffractionLoss: String,
    val totalLoss: String,
    val frequency: String,
    val refraction: String,
)

/** Plain-text summary for share sheets. Field names come from the results-card L10n keys. */
fun formatLineOfSightShareText(
    analysis: PathAnalysisResult,
    clearanceLabel: String,
    elevationNote: String,
    labels: LineOfSightShareLabels,
): String = buildString {
    appendLine(labels.title)
    appendLine(elevationNote)
    appendLine("${labels.distance}: ${analysis.distanceMeters}")
    appendLine("${labels.clearance}: $clearanceLabel")
    appendLine("${labels.freeSpaceLoss}: ${analysis.freeSpacePathLoss} dB")
    appendLine("${labels.diffractionLoss}: ${analysis.peakDiffractionLoss} dB")
    appendLine("${labels.totalLoss}: ${analysis.totalPathLoss} dB")
    appendLine("${labels.frequency}: ${analysis.frequencyMHz} MHz")
    appendLine("${labels.refraction}: ${analysis.refractionK}")
}.trimEnd()

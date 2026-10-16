package one.meshcore.shared.rf

/** Result for a single path segment (A to R, or R to B). */
data class SegmentAnalysisResult(
    val startLabel: String,
    val endLabel: String,
    val clearanceStatus: ClearanceStatus,
    val distanceMeters: Double,
    val worstClearancePercent: Double,
) {
    val distanceKm: Double
        get() = distanceMeters / 1000
}

package one.meshcore.shared.rf

/** Combined result when analyzing a path via a repeater. */
data class RelayPathAnalysisResult(val segmentAR: SegmentAnalysisResult, val segmentRB: SegmentAnalysisResult) {
    val totalDistanceMeters: Double
        get() = segmentAR.distanceMeters + segmentRB.distanceMeters

    val totalDistanceKm: Double
        get() = totalDistanceMeters / 1000

    /** Overall status is the worse of the two segments. */
    val overallStatus: ClearanceStatus
        get() {
            val statusOrder = listOf(
                ClearanceStatus.Clear,
                ClearanceStatus.Marginal,
                ClearanceStatus.PartialObstruction,
                ClearanceStatus.Blocked,
            )
            val arIndex = statusOrder.indexOf(segmentAR.clearanceStatus).coerceAtLeast(0)
            val rbIndex = statusOrder.indexOf(segmentRB.clearanceStatus).coerceAtLeast(0)
            return statusOrder[maxOf(arIndex, rbIndex)]
        }
}

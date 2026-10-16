package one.meshcore.shared.rf

/**
 * Point where an obstruction affects the path. Equality is defined structurally over the three
 * doubles, matching the Swift `ObstructionPoint`, whose per-instance `id` was excluded from `==`.
 * The Swift random `id` is intentionally not ported: it would break structural equality (used by
 * [PathAnalysisResult]); a stable UI identity can be derived from [distanceFromAMeters].
 *
 * `distanceFromAMeters` is always in absolute full-path coordinates, even for a segment analysis.
 */
data class ObstructionPoint(
    val distanceFromAMeters: Double,
    /** Height of the obstruction above line-of-sight; positive means blocked. */
    val obstructionHeightMeters: Double,
    val fresnelClearancePercent: Double,
)

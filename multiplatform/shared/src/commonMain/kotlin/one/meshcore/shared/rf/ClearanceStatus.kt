package one.meshcore.shared.rf

/**
 * Clearance status at the worst point along a path. Raw values are pinned to the Swift strings so
 * any serialized form stays stable. Declaration order is worst-of ordering (clear < marginal <
 * partialObstruction < blocked), relied on by [RelayPathAnalysisResult.overallStatus].
 */
enum class ClearanceStatus(val rawValue: String) {
    Clear("Clear"),
    Marginal("Marginal"),
    PartialObstruction("Partial obstruction"),
    Blocked("Blocked"),
}

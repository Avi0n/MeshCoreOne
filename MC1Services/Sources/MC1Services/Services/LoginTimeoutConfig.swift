import Foundation
import MeshCore

/// Configuration for login timeout based on path length
enum LoginTimeoutConfig {
    /// Base timeout for direct (0-hop) connections
    static let directTimeout: Duration = .seconds(5)

    /// Additional timeout per hop in the path
    static let perHopTimeout: Duration = .seconds(10)

    /// Maximum timeout regardless of path length
    static let maximumTimeout: Duration = .seconds(60)

    /// Calculate appropriate timeout based on path length
    static func timeout(forPathLength pathLength: UInt8) -> Duration {
        let base = directTimeout
        let hopCount = decodePathLen(pathLength)?.hopCount ?? 0
        let additional = perHopTimeout * hopCount
        let total = base + additional
        return min(total, maximumTimeout)
    }
}

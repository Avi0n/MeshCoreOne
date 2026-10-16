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
    // An undecodable byte (mode 3, notably the 0xFF flood sentinel) means no
    // known path: the login floods both ways, so budget for the worst case
    // rather than pricing it as a zero-hop direct exchange.
    guard let decoded = decodePathLen(pathLength) else { return maximumTimeout }
    let total = directTimeout + perHopTimeout * decoded.hopCount
    return min(total, maximumTimeout)
  }
}

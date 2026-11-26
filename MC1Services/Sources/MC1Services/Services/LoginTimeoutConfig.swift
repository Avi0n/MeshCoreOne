import Foundation
import MeshCore

/// Zero-hint fallback (`suggestedTimeoutMs == 0`) for login timeouts based on path length.
enum LoginTimeoutConfig {
  static let directTimeout: Duration = .seconds(5)
  static let perHopTimeout: Duration = .seconds(10)
  static let maximumTimeout = RemoteOperationTimeoutPolicy.loginMaximum

  static func timeout(forPathLength pathLength: UInt8) -> Duration {
    // An undecodable byte (mode 3, notably the 0xFF flood sentinel) means no
    // known path: the login floods both ways, so budget for the worst case
    // rather than pricing it as a zero-hop direct exchange.
    guard let decoded = decodePathLen(pathLength) else { return maximumTimeout }
    let total = directTimeout + perHopTimeout * decoded.hopCount
    return min(total, maximumTimeout)
  }
}

import Foundation

/// Sanitizes the firmware `suggested_timeout_ms` hint used by trace, ping, and
/// path-discovery waits. Scales the hint for slack and clamps it into a
/// per-use-case band. A missing hint (0) uses the profile default so the wait
/// does not expire immediately.
enum FirmwareSuggestedTimeout {
  static let multiplier = 1.2
  private static let millisecondsPerSecond = 1000.0

  /// Per-use-case bounds. A single-neighbor round trip and a mesh-wide flood
  /// need different bands, so callers pick the profile that matches their send.
  struct Profile {
    let minimumSeconds: Double
    let defaultSeconds: Double
    let maximumSeconds: Double
    /// Fixed slack added to the scaled hint before clamping. The hint is based
    /// on outbound airtime; the response leg re-crosses the mesh with delays
    /// the estimate does not include.
    let graceSeconds: Double

    /// Single-neighbor ping to a direct contact. Honors a small valid hint
    /// rather than inflating a fast link to a slow-link default.
    static let zeroHop = Profile(
      minimumSeconds: 1.0, defaultSeconds: 5.0, maximumSeconds: 30.0, graceSeconds: 0.0
    )

    /// Flood path discovery and multi-hop traces. Flood `est_timeout` is
    /// outbound airtime only and hop-blind; grace covers return-leg jitter.
    static let flood = Profile(
      minimumSeconds: 5.0, defaultSeconds: 30.0, maximumSeconds: 60.0, graceSeconds: 8.0
    )
  }

  /// Floor for Discover Path overall wait. Flood `est_timeout` underestimates
  /// multi-hop return, so small hints still get a useful budget.
  static let pathDiscoveryMinimumOverallSeconds = 20.0

  /// Retransmit spacing multiplier on firmware est. Companion holds one
  /// `pending_discovery` tag and replaces it on each send; headroom 2 leaves
  /// room for a first reply before the next resend.
  static let pathDiscoveryRetransmitRTTHeadroom = 2

  /// Minimum spacing between path-discovery resends. Flood is expensive; never
  /// pace faster than this even when 2× est is shorter.
  static let pathDiscoveryRetransmitFloor: Duration = .seconds(5)

  /// Scaled hint before clamping. Used for logging the raw estimate next to
  /// the value actually applied.
  static func candidateSeconds(suggestedTimeoutMs: UInt32) -> Double {
    Double(suggestedTimeoutMs) / millisecondsPerSecond * multiplier
  }

  /// Scaled hint plus profile grace, clamped into the profile band. A missing
  /// hint (0) yields the profile default.
  static func sanitizedSeconds(suggestedTimeoutMs: UInt32, profile: Profile) -> Double {
    guard suggestedTimeoutMs > 0 else { return profile.defaultSeconds }
    let candidate = candidateSeconds(suggestedTimeoutMs: suggestedTimeoutMs) + profile.graceSeconds
    return min(max(candidate, profile.minimumSeconds), profile.maximumSeconds)
  }

  /// Discover Path overall wait: flood-sanitized hint, at least
  /// `pathDiscoveryMinimumOverallSeconds`.
  static func pathDiscoverySeconds(suggestedTimeoutMs: UInt32) -> Double {
    max(
      pathDiscoveryMinimumOverallSeconds,
      sanitizedSeconds(suggestedTimeoutMs: suggestedTimeoutMs, profile: .flood)
    )
  }

  /// Spacing between path-discovery resends, or `nil` when firmware gave no
  /// hint (single send for the whole budget).
  static func pathDiscoveryRetransmitInterval(suggestedTimeoutMs: UInt32) -> Duration? {
    guard suggestedTimeoutMs > 0 else { return nil }
    let headed = Int(suggestedTimeoutMs) * pathDiscoveryRetransmitRTTHeadroom
    return max(pathDiscoveryRetransmitFloor, .milliseconds(headed))
  }
}

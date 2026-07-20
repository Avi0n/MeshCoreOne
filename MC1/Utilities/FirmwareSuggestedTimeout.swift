import Foundation

/// Sanitizes the firmware's `suggested_timeout_ms` hint shared by trace, ping,
/// and path-discovery sends. The hint is the radio's airtime estimate, already
/// hop-aware, so it is honored: scale it for slack and clamp into a per-use-case
/// band. Only a missing hint (0) falls back to a default, because a hint of 0
/// would otherwise make every wait expire immediately.
enum FirmwareSuggestedTimeout {
  static let multiplier = 1.2
  private static let millisecondsPerSecond = 1000.0

  /// Per-use-case bounds. A single-neighbor round trip and a mesh-wide flood
  /// need very different bands, so callers pick the one matching their send.
  struct Profile {
    let minimumSeconds: Double
    let defaultSeconds: Double
    let maximumSeconds: Double
    /// Fixed slack added to the scaled hint before clamping. The hint scales
    /// off the outbound request's airtime alone, but the response leg
    /// re-crosses the mesh with per-hop random retransmit delays the estimate
    /// can't see, so responses routinely land just after the scaled hint.
    let graceSeconds: Double

    /// Single-neighbor ping to a direct contact. The round trip is one hop, so a
    /// valid hint is small (a few seconds on common presets); honor it down to a
    /// second rather than inflating a fast link to a slow link's budget. The
    /// ceiling still covers a legitimate max-range preset, where one zero-hop
    /// round trip at SF12/BW125 genuinely approaches half a minute.
    static let zeroHop = Profile(
      minimumSeconds: 1.0, defaultSeconds: 5.0, maximumSeconds: 30.0, graceSeconds: 0.0
    )

    /// Flood path discovery and user-built multi-hop traces: the request can cross
    /// the whole mesh and legitimately take tens of seconds, so keep a wide band
    /// and grant grace for the multi-hop return leg.
    static let flood = Profile(
      minimumSeconds: 5.0, defaultSeconds: 30.0, maximumSeconds: 60.0, graceSeconds: 5.0
    )
  }

  /// The scaled hint before clamping. Exposed so callers can log the raw estimate
  /// alongside the value actually used.
  static func candidateSeconds(suggestedTimeoutMs: UInt32) -> Double {
    Double(suggestedTimeoutMs) / millisecondsPerSecond * multiplier
  }

  /// The scaled hint plus the profile's grace, clamped into `profile`'s band.
  /// A missing hint (0) yields the profile default; a valid-but-small hint is
  /// honored down to the floor rather than discarded, so a fast link isn't made
  /// to wait out a slow link's default.
  static func sanitizedSeconds(suggestedTimeoutMs: UInt32, profile: Profile) -> Double {
    guard suggestedTimeoutMs > 0 else { return profile.defaultSeconds }
    let candidate = candidateSeconds(suggestedTimeoutMs: suggestedTimeoutMs) + profile.graceSeconds
    return min(max(candidate, profile.minimumSeconds), profile.maximumSeconds)
  }
}

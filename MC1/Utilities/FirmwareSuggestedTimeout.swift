import Foundation

/// Sanitizes the firmware's `suggested_timeout_ms` hint shared by trace, ping,
/// and path-discovery sends. The hint is the radio's airtime estimate; scale it
/// for slack and reject implausible values (a hint of 0 would otherwise make
/// every wait expire immediately).
enum FirmwareSuggestedTimeout {
  static let multiplier = 1.2
  static let minimumSeconds = 5.0
  static let maximumSeconds = 60.0
  static let defaultSeconds = 30.0
  private static let millisecondsPerSecond = 1000.0

  /// The scaled hint before bounds checking. Exposed so callers can log the
  /// rejected value when `sanitizedSeconds` falls back to the default.
  static func candidateSeconds(suggestedTimeoutMs: UInt32) -> Double {
    Double(suggestedTimeoutMs) / millisecondsPerSecond * multiplier
  }

  /// The scaled hint when it lands within sane bounds, otherwise `defaultSeconds`.
  static func sanitizedSeconds(suggestedTimeoutMs: UInt32) -> Double {
    let candidateSeconds = Self.candidateSeconds(suggestedTimeoutMs: suggestedTimeoutMs)
    guard candidateSeconds >= minimumSeconds, candidateSeconds <= maximumSeconds else {
      return defaultSeconds
    }
    return candidateSeconds
  }
}

import Foundation

/// Keeps a paired radio's `tz.offset` aligned with the phone's current offset.
@MainActor
enum RadioTimezone {
  private static let minHours = -12
  private static let maxHours = 14
  private static let secondsPerHour = 3600
  private static let logger = PersistentLogger(subsystem: "com.mc1", category: "RadioTimezone")

  /// Whole hours in -12...14. Fractional and out-of-range offsets return nil.
  static func offsetHours(secondsFromGMT: Int) -> Int? {
    let remainder = secondsFromGMT % secondsPerHour
    guard remainder == 0 else { return nil }
    let hours = secondsFromGMT / secondsPerHour
    guard hours >= minHours, hours <= maxHours else { return nil }
    return hours
  }

  /// Writes the phone offset only when the radio differs. A bad reply is logged and does not block promotion.
  /// `stillCurrent` is checked after the read so a replaced connection is not written.
  static func syncIfNeeded(
    supportsRadioCLI: Bool,
    secondsFromGMT: Int,
    settings: SettingsService,
    stillCurrent: @MainActor () -> Bool
  ) async {
    guard supportsRadioCLI, let hours = offsetHours(secondsFromGMT: secondsFromGMT) else { return }
    do {
      let current = try await settings.getRadioCLIValue(RadioCLIKey.timezoneOffset)
      guard stillCurrent() else { return }
      if let current {
        let parsed = Int(current.trimmingCharacters(in: .whitespacesAndNewlines))
        if parsed == hours { return }
      }
      try await settings.setRadioCLIValue(RadioCLIKey.timezoneOffset, to: String(hours))
    } catch {
      logger.warning("Failed to sync radio timezone: \(error.localizedDescription)")
    }
  }
}

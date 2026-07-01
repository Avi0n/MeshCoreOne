import SwiftUI

/// Shared tuning and math for BLE signal-strength display.
///
/// Used by both the iOS device picker (`DeviceSelectionSheet`) and the macOS scan picker
/// (`DeviceScannerSheet`) so the two surfaces apply identical thresholds, smoothing, and
/// hysteresis. The scan and expiry orchestration that drives this math is shared via
/// `RSSIScanTracker`, so neither the math nor its orchestration can drift between the pickers.
enum RSSITuning {
  /// Discrete signal-strength tier derived from a smoothed RSSI reading. Raw values are stable
  /// (0 weak, 1 medium, 2 strong) so the glyph fill and color helpers map them directly.
  enum SignalTier: Int {
    case weak = 0
    case medium = 1
    case strong = 2
  }

  /// RSSI (dBm) at or above which signal is shown as full strength (tier 2 / green).
  static let strongThreshold = -60
  /// RSSI (dBm) at or above which signal is shown as medium (tier 1 / yellow);
  /// below this is weak (tier 0 / red).
  static let mediumThreshold = -80
  /// Margin (dBm) a reading must clear before the displayed tier changes, so the
  /// glyph does not flicker when the signal hovers at a boundary.
  static let tierHysteresis = 3
  /// Weight applied to the newest sample in the exponential RSSI smoothing
  /// (`new · weight + previous · (1 − weight)`).
  static let smoothingNewWeight = 0.2
  /// Sentinel RSSI CoreBluetooth reports when signal strength is unavailable.
  static let unavailableRSSI = -127
  /// Cadence of the stale-peripheral expiry sweep.
  static let expiryTick: Duration = .seconds(2)
  /// Seconds without a fresh advertisement before a peripheral is treated as gone.
  /// Shared by both pickers so a device drops (macOS scanner) or goes non-tappable
  /// (iOS picker) on the same schedule; with the 2s sweep, real staleness is up to
  /// `staleWindow + expiryTick`.
  static let staleWindow: TimeInterval = 4

  /// Whether an RSSI reading is usable: a negative dBm value that is not the
  /// `unavailableRSSI` sentinel (0 or positive readings also indicate unavailable).
  static func isUsable(_ rssi: Int) -> Bool {
    rssi < 0 && rssi != unavailableRSSI
  }

  /// Exponentially smooths a new RSSI sample against the previous smoothed value.
  /// Returns the new sample unchanged when there is no prior reading.
  static func smooth(newRSSI: Int, previousRSSI: Int?) -> Int {
    guard let previousRSSI else { return newRSSI }
    return Int(smoothingNewWeight * Double(newRSSI) + (1 - smoothingNewWeight) * Double(previousRSSI))
  }

  /// Signal tier with hysteresis: a reading must clear a threshold by `tierHysteresis` dBm
  /// before the displayed tier changes. The first reading (`currentTier == nil`) maps directly
  /// with no hysteresis.
  static func tier(currentTier: SignalTier?, smoothedRSSI: Int) -> SignalTier {
    switch currentTier {
    case .strong: // drop a tier only if clearly below threshold
      return smoothedRSSI < strongThreshold - tierHysteresis
        ? (smoothedRSSI < mediumThreshold - tierHysteresis ? .weak : .medium) : .strong
    case .medium: // need margin to move up or down
      if smoothedRSSI >= strongThreshold + tierHysteresis { return .strong }
      if smoothedRSSI < mediumThreshold - tierHysteresis { return .weak }
      return .medium
    case .weak: // need margin to move up
      return smoothedRSSI >= mediumThreshold + tierHysteresis
        ? (smoothedRSSI >= strongThreshold + tierHysteresis ? .strong : .medium) : .weak
    case nil: // first reading, no hysteresis
      if smoothedRSSI >= strongThreshold { return .strong }
      if smoothedRSSI >= mediumThreshold { return .medium }
      return .weak
    }
  }

  /// Fill level (0...1) for the `cellularbars` glyph at a given tier.
  static func fillLevel(forTier tier: SignalTier) -> Double {
    switch tier {
    case .strong: 1.0
    case .medium: 0.66
    case .weak: 0.33
    }
  }

  /// Color for the signal glyph at a given tier.
  static func color(forTier tier: SignalTier) -> Color {
    switch tier {
    case .strong: .green
    case .medium: .yellow
    case .weak: .red
    }
  }
}

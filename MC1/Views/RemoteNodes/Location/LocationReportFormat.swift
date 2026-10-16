import CoreLocation
import Foundation

/// Formats a single location report (one `NodeStatusSnapshotDTO`) for the
/// History list: a coarse relative recency, a precise absolute timestamp, and
/// fixed-precision coordinates. Pure and side-effect free so rows stay trivially
/// unit-testable.
enum LocationReportFormat {
  /// Decimal places shown for latitude/longitude. Four places (about 11 m) is
  /// enough to distinguish adjacent reports without implying false GPS precision.
  private static let coordinateDecimals = 4

  /// "2h ago": single-unit localized recency. Coarse by design: reports land
  /// ~15 min apart, so the absolute timestamp is what distinguishes adjacent rows.
  /// Built locally per call: `RelativeDateTimeFormatter` is not `Sendable`, so a
  /// shared static can't be nonisolated, and the History list is small.
  static func relativeTime(for date: Date, relativeTo now: Date) -> String {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter.localizedString(for: date, relativeTo: now)
  }

  /// "Jul 13, 07:41": localized absolute timestamp of the report.
  static func absoluteTime(for date: Date) -> String {
    date.formatted(.dateTime.month(.abbreviated).day().hour().minute())
  }

  /// "37.7847, -122.4012": fixed-precision coordinates with a locale-independent
  /// decimal point (coordinates are conventionally dotted, never comma-separated).
  static func coordinates(_ coordinate: CLLocationCoordinate2D) -> String {
    String(
      format: "%.\(coordinateDecimals)f, %.\(coordinateDecimals)f",
      coordinate.latitude,
      coordinate.longitude
    )
  }

  /// "42 m" / "138 ft": altitude in the region's preferred length unit. The
  /// stored value is meters; `.naturalScale` converts to feet where the locale
  /// uses imperial. Rounded to whole units, since GPS altitude precision doesn't
  /// justify decimals.
  static func altitude(_ meters: Double) -> String {
    let formatter = MeasurementFormatter()
    formatter.unitOptions = .naturalScale
    formatter.numberFormatter.maximumFractionDigits = 0
    return formatter.string(from: Measurement(value: meters, unit: UnitLength.meters))
  }
}

import CoreLocation
import MeshCore

/// A single GPS fix persisted with a snapshot. Latitude and longitude travel
/// together so a snapshot can never hold half a fix.
public struct NodeLocationFix: Sendable, Equatable, Hashable {
  public let latitude: Double
  public let longitude: Double
  /// Altitude in meters, or nil when the fix carried none or an implausible one.
  /// The `.gps` LPP value decodes to meters already (0.01 m on the wire).
  public let altitude: Double?

  /// Plausible altitude range in meters, roughly below the lowest dry land to
  /// above jet cruising altitude. Values outside are treated as noise and
  /// dropped to nil. 0 (sea level) is inside the range and retained, unlike the
  /// (0,0) null-island guard on latitude/longitude.
  private static let altitudeRange: ClosedRange<Double> = -500...10000

  public init(latitude: Double, longitude: Double, altitude: Double? = nil) {
    self.latitude = latitude
    self.longitude = longitude
    self.altitude = altitude
  }

  public var coordinate: CLLocationCoordinate2D {
    CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
  }

  /// The primary fix for a telemetry reading: the first GPS data point, kept only
  /// when it resolves to a plottable coordinate. A missing GPS point or a
  /// (0,0)/out-of-range fix returns nil so a lock-less node stores no location.
  /// Altitude rides along when present and plausible; it never gates the fix.
  public static func primaryFix(from dataPoints: [LPPDataPoint]) -> NodeLocationFix? {
    for point in dataPoints {
      guard case let .gps(latitude, longitude, altitude) = point.value else { continue }
      let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
      guard coordinate.isValidFix else { return nil }
      let sanitizedAltitude = altitudeRange.contains(altitude) ? altitude : nil
      return NodeLocationFix(latitude: latitude, longitude: longitude, altitude: sanitizedAltitude)
    }
    return nil
  }
}

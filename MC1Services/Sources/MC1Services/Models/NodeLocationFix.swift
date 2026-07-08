import CoreLocation
import MeshCore

/// A single GPS fix persisted with a snapshot. Latitude and longitude travel
/// together so a snapshot can never hold half a fix.
public struct NodeLocationFix: Sendable, Equatable {
  public let latitude: Double
  public let longitude: Double

  public init(latitude: Double, longitude: Double) {
    self.latitude = latitude
    self.longitude = longitude
  }

  public var coordinate: CLLocationCoordinate2D {
    CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
  }

  /// The primary fix for a telemetry reading: the first GPS data point, kept only
  /// when it resolves to a plottable coordinate. A missing GPS point or a
  /// (0,0)/out-of-range fix returns nil so a lock-less node stores no location.
  public static func primaryFix(from dataPoints: [LPPDataPoint]) -> NodeLocationFix? {
    for point in dataPoints {
      guard case let .gps(latitude, longitude, _) = point.value else { continue }
      let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
      guard coordinate.isValidFix else { return nil }
      return NodeLocationFix(latitude: latitude, longitude: longitude)
    }
    return nil
  }
}

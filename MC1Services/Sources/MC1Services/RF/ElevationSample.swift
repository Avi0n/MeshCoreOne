import CoreLocation
import Foundation

/// Elevation sample along the path
public struct ElevationSample: Identifiable, Sendable {
    public let id = UUID()
    public let coordinate: CLLocationCoordinate2D
    public let elevation: Double  // meters above sea level
    public let distanceFromAMeters: Double

    public init(coordinate: CLLocationCoordinate2D, elevation: Double, distanceFromAMeters: Double) {
        self.coordinate = coordinate
        self.elevation = elevation
        self.distanceFromAMeters = distanceFromAMeters
    }
}

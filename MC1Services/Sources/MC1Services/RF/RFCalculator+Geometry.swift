import CoreLocation
import Foundation

extension RFCalculator {
  private static let metersPerKilometer: Double = 1000
  private static let degreesPerHalfTurn: Double = 180
  private static let angularDegeneracyTolerance = 64 * Double.ulpOfOne

  /// Returns the great-circle surface distance in meters, or NaN for invalid coordinates.
  public static func distance(from: CLLocationCoordinate2D, to destination: CLLocationCoordinate2D) -> Double {
    guard CLLocationCoordinate2DIsValid(from), CLLocationCoordinate2DIsValid(destination) else { return .nan }
    let earthRadiusMeters = earthRadiusKm * metersPerKilometer
    let lat1 = from.latitude * .pi / degreesPerHalfTurn
    let lat2 = destination.latitude * .pi / degreesPerHalfTurn
    let deltaLat = (destination.latitude - from.latitude) * .pi / degreesPerHalfTurn
    let deltaLon = (destination.longitude - from.longitude) * .pi / degreesPerHalfTurn

    let haversineA = sin(deltaLat / 2) * sin(deltaLat / 2) +
      cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    let boundedHaversine = min(1, max(0, haversineA))
    let angularDistance = 2 * atan2(sqrt(boundedHaversine), sqrt(1 - boundedHaversine))
    return earthRadiusMeters * angularDistance
  }

  /// Interpolates along the shortest spherical arc; antipodal endpoints have no unique arc.
  public static func interpolateCoordinate(
    from: CLLocationCoordinate2D,
    to destination: CLLocationCoordinate2D,
    fraction: Double
  ) -> CLLocationCoordinate2D? {
    guard CLLocationCoordinate2DIsValid(from), CLLocationCoordinate2DIsValid(destination),
          fraction.isFinite, (0...1).contains(fraction) else { return nil }
    let start = UnitVector(coordinate: from)
    let end = UnitVector(coordinate: destination)
    let dot = min(1, max(-1, start.x * end.x + start.y * end.y + start.z * end.z))
    let crossX = start.y * end.z - start.z * end.y
    let crossY = start.z * end.x - start.x * end.z
    let crossZ = start.x * end.y - start.y * end.x
    let crossLength = hypot(hypot(crossX, crossY), crossZ)

    guard crossLength > angularDegeneracyTolerance || dot >= 0 else { return nil }
    if fraction == 0 { return from }
    if fraction == 1 { return destination }
    if crossLength <= angularDegeneracyTolerance {
      return from
    }

    let angle = atan2(crossLength, dot)
    let startWeight = sin((1 - fraction) * angle) / crossLength
    let endWeight = sin(fraction * angle) / crossLength
    let x = startWeight * start.x + endWeight * end.x
    let y = startWeight * start.y + endWeight * end.y
    let z = startWeight * start.z + endWeight * end.z
    return CLLocationCoordinate2D(
      latitude: atan2(z, hypot(x, y)) * degreesPerHalfTurn / .pi,
      longitude: atan2(y, x) * degreesPerHalfTurn / .pi
    )
  }

  private struct UnitVector {
    let x: Double
    let y: Double
    let z: Double

    init(coordinate: CLLocationCoordinate2D) {
      let latitude = coordinate.latitude * .pi / RFCalculator.degreesPerHalfTurn
      let longitude = coordinate.longitude * .pi / RFCalculator.degreesPerHalfTurn
      x = cos(latitude) * cos(longitude)
      y = cos(latitude) * sin(longitude)
      z = sin(latitude)
    }
  }
}

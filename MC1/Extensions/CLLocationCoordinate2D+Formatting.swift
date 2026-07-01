import CoreLocation

extension CLLocationCoordinate2D {
  var formattedString: String {
    "\(latitude.formatted(.number.precision(.fractionLength(6)))), \(longitude.formatted(.number.precision(.fractionLength(6))))"
  }
}

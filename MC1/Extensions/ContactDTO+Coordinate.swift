import CoreLocation
import MC1Services

extension ContactDTO {
  var coordinate: CLLocationCoordinate2D {
    CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
  }
}

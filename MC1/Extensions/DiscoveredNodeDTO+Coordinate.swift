import CoreLocation
import MC1Services

extension DiscoveredNodeDTO {
  var coordinate: CLLocationCoordinate2D {
    CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
  }
}

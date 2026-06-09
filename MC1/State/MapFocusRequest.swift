import CoreLocation

/// A pending request to focus the Map tab on a coordinate.
///
/// Wraps the coordinate in an `Equatable` value because `CLLocationCoordinate2D`
/// is not `Equatable`, which `MapView`'s `.onChange(of:)` requires.
struct MapFocusRequest: Equatable {
    let latitude: Double
    let longitude: Double

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

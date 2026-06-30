import CoreLocation

extension Array where Element == CLLocationCoordinate2D {
    /// Total length in meters along the ordered coordinate chain, summing
    /// great-circle (WGS-84) distance between consecutive points. Nil for
    /// fewer than 2 points.
    ///
    /// Separate from `TracePathViewModel.calculateDistance(for:)` on purpose:
    /// that helper is all-or-nothing (nil if any hop lacks a location), whereas
    /// callers here pass already-located coordinates, so this always succeeds.
    func totalDistance() -> CLLocationDistance? {
        guard count >= 2 else { return nil }
        var meters: CLLocationDistance = 0
        for index in 0..<(count - 1) {
            let origin = CLLocation(latitude: self[index].latitude, longitude: self[index].longitude)
            let destination = CLLocation(latitude: self[index + 1].latitude, longitude: self[index + 1].longitude)
            meters += origin.distance(from: destination)
        }
        return meters
    }
}

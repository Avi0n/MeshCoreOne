import MapKit

/// Serializes a map camera region to and from the compact string `@SceneStorage`
/// persists. `decode` is the trust boundary: it rejects every value that aborts
/// MapLibre (invalid coordinate, non-finite or non-positive span), so a malformed
/// restoration archive can never feed `MLNCoordinateBounds` a process-aborting value.
enum MapCameraStore {

    /// Component count of the `lat,lon,latDelta,lonDelta` encoding.
    private static let componentCount = 4

    static func encode(_ region: MKCoordinateRegion) -> String {
        "\(region.center.latitude),\(region.center.longitude),"
            + "\(region.span.latitudeDelta),\(region.span.longitudeDelta)"
    }

    static func decode(_ string: String) -> MKCoordinateRegion? {
        let parts = string.split(separator: ",").compactMap { Double($0) }
        guard parts.count == componentCount else { return nil }

        let center = CLLocationCoordinate2D(latitude: parts[0], longitude: parts[1])
        let span = MKCoordinateSpan(latitudeDelta: parts[2], longitudeDelta: parts[3])
        guard CLLocationCoordinate2DIsValid(center),
              span.latitudeDelta.isFinite, span.longitudeDelta.isFinite,
              span.latitudeDelta > 0, span.longitudeDelta > 0 else {
            return nil
        }
        return MKCoordinateRegion(center: center, span: span)
    }
}

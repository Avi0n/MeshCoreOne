import CoreLocation
import MapKit
import MapLibre

extension Array where Element == CLLocationCoordinate2D {
    /// Computes a bounding `MKCoordinateRegion` that fits all coordinates with padding.
    func boundingRegion(paddingMultiplier: Double = 1.5) -> MKCoordinateRegion? {
        guard let first else { return nil }

        var minLat = first.latitude, maxLat = first.latitude
        var minLon = first.longitude, maxLon = first.longitude

        for coord in dropFirst() {
            minLat = Swift.min(minLat, coord.latitude)
            maxLat = Swift.max(maxLat, coord.latitude)
            minLon = Swift.min(minLon, coord.longitude)
            maxLon = Swift.max(maxLon, coord.longitude)
        }

        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        let rawLatDelta = Swift.max(0.01, (maxLat - minLat) * paddingMultiplier)
        let rawLonDelta = Swift.max(0.01, (maxLon - minLon) * paddingMultiplier)

        // Clamp spans so center ± span/2 stays within valid coordinate ranges
        let maxLatDelta = (90 - abs(center.latitude)) * 2
        let latDelta = Swift.min(rawLatDelta, maxLatDelta)
        let lonDelta = Swift.min(rawLonDelta, 360)

        return MKCoordinateRegion(
            center: center,
            span: MKCoordinateSpan(latitudeDelta: latDelta, longitudeDelta: lonDelta)
        )
    }
}

extension MKCoordinateRegion {
    func toMLNCoordinateBounds() -> MLNCoordinateBounds {
        MLNCoordinateBounds(
            sw: CLLocationCoordinate2D(
                latitude: center.latitude - span.latitudeDelta / 2,
                longitude: center.longitude - span.longitudeDelta / 2
            ),
            ne: CLLocationCoordinate2D(
                latitude: center.latitude + span.latitudeDelta / 2,
                longitude: center.longitude + span.longitudeDelta / 2
            )
        )
    }
}

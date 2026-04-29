import CoreLocation
import Foundation

/// Reverse-geocoder protocol used by `RegionResolver`. Test doubles can stub
/// the placemark return without going through `CLGeocoder`.
public protocol Geocoder: Sendable {
    func reverseGeocode(_ location: CLLocation, preferredLocale: Locale?) async throws -> CLPlacemark?
}

public struct AppleGeocoder: Geocoder {
    public init() {}

    public func reverseGeocode(_ location: CLLocation, preferredLocale: Locale?) async throws -> CLPlacemark? {
        let placemarks = try await CLGeocoder().reverseGeocodeLocation(location, preferredLocale: preferredLocale)
        return placemarks.first
    }
}

import CoreLocation
import Foundation

/// Reverse-geocoder protocol used by `RegionResolver`. Returns a `Sendable`
/// `GeocodeResult` so test doubles don't need to construct `CLPlacemark` (which
/// has no usable initializer outside CoreLocation).
public protocol Geocoder: Sendable {
    func reverseGeocode(_ location: CLLocation, preferredLocale: Locale?) async throws -> GeocodeResult?
    func cancelGeocode()
}

public struct GeocodeResult: Sendable, Equatable {
    public let countryCode: String?
    public let administrativeArea: String?
    public let subAdministrativeArea: String?

    public init(countryCode: String?, administrativeArea: String?, subAdministrativeArea: String?) {
        self.countryCode = countryCode
        self.administrativeArea = administrativeArea
        self.subAdministrativeArea = subAdministrativeArea
    }
}

/// `CLGeocoder`-backed implementation. Retains a single `CLGeocoder` instance
/// so cancellation actually cancels the in-flight request — using a fresh
/// instance per call (the previous shape) made `cancelGeocode()` a no-op.
public final class AppleGeocoder: Geocoder, @unchecked Sendable {
    private let geocoder = CLGeocoder()

    public init() {}

    public func reverseGeocode(_ location: CLLocation, preferredLocale: Locale?) async throws -> GeocodeResult? {
        let placemarks = try await geocoder.reverseGeocodeLocation(location, preferredLocale: preferredLocale)
        guard let placemark = placemarks.first else { return nil }
        return GeocodeResult(
            countryCode: placemark.isoCountryCode,
            administrativeArea: placemark.administrativeArea,
            subAdministrativeArea: placemark.subAdministrativeArea
        )
    }

    public func cancelGeocode() {
        geocoder.cancelGeocode()
    }
}

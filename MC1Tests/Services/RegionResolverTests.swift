import CoreLocation
import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("RegionResolver")
@MainActor
struct RegionResolverTests {

    // MARK: - Test doubles

    private final class StubGeocoder: Geocoder, @unchecked Sendable {
        var stub: GeocodeResult?
        var error: Error?
        private(set) var cancelGeocodeCallCount = 0

        func reverseGeocode(_ location: CLLocation, preferredLocale: Locale?) async throws -> GeocodeResult? {
            if let error { throw error }
            return stub
        }

        func cancelGeocode() {
            cancelGeocodeCallCount += 1
        }
    }

    // MARK: - Failure paths
    //
    // These three tests exercise the `location.isAuthorized` guard — the resolver
    // returns nil before the geocoder runs when authorization is undetermined.
    // Success-path coverage requires injecting a stubbed `LocationService`, which
    // is a follow-up (LocationService is not currently abstracted behind a
    // protocol).

    @Test("nil isoCountryCode → nil")
    func nilCountryCodeReturnsNil() async {
        let location = LocationService()
        let geocoder = StubGeocoder()
        geocoder.stub = nil
        let resolver = RegionResolver(location: location, geocoder: geocoder)
        let result = await resolver.resolve()
        #expect(result == nil)
    }

    @Test("Geocoder error → nil")
    func geocoderErrorReturnsNil() async {
        let location = LocationService()
        let geocoder = StubGeocoder()
        geocoder.error = NSError(domain: "test", code: -1)
        let resolver = RegionResolver(location: location, geocoder: geocoder)
        let result = await resolver.resolve()
        #expect(result == nil)
    }

    @Test("Unauthorized location → nil")
    func unauthorizedReturnsNil() async {
        let location = LocationService()  // .notDetermined by default
        let geocoder = StubGeocoder()
        let resolver = RegionResolver(location: location, geocoder: geocoder)
        let result = await resolver.resolve()
        #expect(result == nil)
    }
}

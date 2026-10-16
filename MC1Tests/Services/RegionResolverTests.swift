import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("RegionResolver")
@MainActor
struct RegionResolverTests {
  // MARK: - Test doubles

  private final class StubGeocoder: Geocoder, @unchecked Sendable {
    var stub: GeocodeResult?
    private(set) var cancelGeocodeCallCount = 0

    func reverseGeocode(_ location: CLLocation, preferredLocale: Locale?) async throws -> GeocodeResult? {
      stub
    }

    func cancelGeocode() {
      cancelGeocodeCallCount += 1
    }
  }

  // MARK: - Failure paths

  //
  // These tests exercise the `location.isAuthorized` guard — the resolver
  // returns nil before the geocoder runs when authorization is undetermined.
  // Success-path coverage requires injecting a stubbed `LocationService`, which
  // is a follow-up (LocationService is not currently abstracted behind a
  // protocol).

  @Test
  func `nil isoCountryCode → nil`() async {
    let location = LocationService()
    let geocoder = StubGeocoder()
    geocoder.stub = nil
    let resolver = RegionResolver(location: location, geocoder: geocoder)
    let result = await resolver.resolve()
    #expect(result == nil)
  }

  @Test
  func `Unauthorized location → nil`() async {
    let location = LocationService() // .notDetermined by default
    let geocoder = StubGeocoder()
    let resolver = RegionResolver(location: location, geocoder: geocoder)
    let result = await resolver.resolve()
    #expect(result == nil)
  }
}

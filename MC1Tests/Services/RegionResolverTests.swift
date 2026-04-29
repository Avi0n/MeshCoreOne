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
        var stub: CLPlacemark?
        var error: Error?
        func reverseGeocode(_ location: CLLocation, preferredLocale: Locale?) async throws -> CLPlacemark? {
            if let error { throw error }
            return stub
        }
    }

    private func makePlacemark(
        country: String? = nil,
        admin: String? = nil,
        subAdmin: String? = nil
    ) -> CLPlacemark {
        PlacemarkShim(country: country, admin: admin, subAdmin: subAdmin)
    }

    // MARK: - Failure paths

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

    // Note: Tests for the success path require a CLLocation/CLPlacemark stub.
    // PlacemarkShim above is a placeholder — Task 2.3 includes a follow-up
    // checkbox to wire a real `CLPlacemarkProtocol` if NSKeyedArchiver-based
    // construction proves brittle on the iPhone 17e simulator. Document
    // resolver coverage gap if so.
}

// PlacemarkShim wraps CLPlacemark via NSKeyedUnarchiver to avoid calling
// the unavailable CLPlacemark.init() directly. It provides controllable
// isoCountryCode, administrativeArea, and subAdministrativeArea for success-path tests.
private final class PlacemarkShim: CLPlacemark, @unchecked Sendable {
    private let _country: String?
    private let _admin: String?
    private let _subAdmin: String?

    init(country: String?, admin: String?, subAdmin: String?) {
        self._country = country
        self._admin = admin
        self._subAdmin = subAdmin
        // CLPlacemark.init() is API_UNAVAILABLE. Use init(placemark:) with a
        // minimal NSKeyedUnarchiver-constructed seed to satisfy the designated init requirement.
        let seed = PlacemarkShim.makeSeed()
        super.init(placemark: seed)
    }

    required init?(coder: NSCoder) { nil }

    override var isoCountryCode: String? { _country }
    override var administrativeArea: String? { _admin }
    override var subAdministrativeArea: String? { _subAdmin }

    // Builds the minimal CLPlacemark archive required by init(placemark:).
    // The archive only needs to decode successfully — all properties are
    // overridden by this subclass and are never read from the seed.
    private static func makeSeed() -> CLPlacemark {
        // Minimal NSKeyedArchiver plist that CLPlacemark.initWithCoder: accepts.
        // Keys discovered by archiving a real CLPlacemark from CLGeocoder.
        let nullRef: [String: Any] = ["CF$UID": 0]
        let classRef: [String: Any] = ["CF$UID": 2]
        let placemarkObj: [String: Any] = [
            "kCLPlacemarkCodingKeyAddress": nullRef,
            "kCLPlacemarkCodingKeyLocation": nullRef,
            "kCLPlacemarkCodingKeyRegion": nullRef,
            "kCLPlacemarkCodingKeyAreasOfInterest": nullRef,
            "kCLPlacemarkCodingKeyGEOMapItem": nullRef,
            "kCLPlacemarkCodingKeyGEOMapItemHandle": nullRef,
            "kCLPlacemarkCodingKeyMapItemSource": nullRef,
            "kCLPlacemarkCodingKeyMeCardAddress": nullRef,
            "kCLPlacemarkCodingKeyMuid": nullRef,
            "kCLPlacemarkCodingKeyCategory": nullRef,
            "$class": classRef
        ]
        let classDesc: [String: Any] = [
            "$classname": "CLPlacemark",
            "$classes": ["CLPlacemark", "NSObject"]
        ]
        let plist: [String: Any] = [
            "$version": 100000,
            "$archiver": "NSKeyedArchiver",
            "$top": ["root": ["CF$UID": 1] as [String: Any]],
            "$objects": ["$null", placemarkObj, classDesc] as [Any]
        ]
        guard let data = try? PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0),
              let unarchiver = try? NSKeyedUnarchiver(forReadingFrom: data) else {
            fatalError("PlacemarkShim: failed to serialize seed plist")
        }
        unarchiver.requiresSecureCoding = false
        guard let seed = unarchiver.decodeObject(of: CLPlacemark.self, forKey: NSKeyedArchiveRootObjectKey) else {
            fatalError("PlacemarkShim: failed to decode seed CLPlacemark")
        }
        return seed
    }
}

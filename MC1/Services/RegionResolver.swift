import CoreLocation
import Foundation
import MC1Services
import OSLog

/// Resolves a `RegionSelection` from the device's current location.
///
/// Lives in MC1 (next to `LocationService`) because MC1Services is intentionally
/// CoreLocation-free. The resolver is a one-shot orchestrator — views observe
/// `AppState.regionSelection`, not this object — so it is not `@Observable`.
@MainActor
public final class RegionResolver {

    public static let resolveTimeout: Duration = .seconds(5)
    public static let cacheTTL: TimeInterval = 24 * 60 * 60

    private static let geocodingLocale = Locale(identifier: "en_US")
    private static let countySuffix = " county"

    private let logger = Logger(subsystem: "com.mc1", category: "RegionResolver")
    private let location: LocationService
    private let geocoder: any Geocoder
    private var cache: [CacheKey: CachedResult] = [:]

    public init(location: LocationService, geocoder: any Geocoder = AppleGeocoder()) {
        self.location = location
        self.geocoder = geocoder
    }

    /// Returns a `RegionSelection` derived from the device's current location, or
    /// nil for any failure (denied, timeout, no network, nil isoCountryCode).
    /// Failure modes are silent — callers fall through to manual picker.
    public func resolve() async -> RegionSelection? {
        guard location.isAuthorized else { return nil }
        do {
            let loc = try await location.requestCurrentLocation(timeout: Self.resolveTimeout)
            let key = CacheKey(loc)
            if let cached = cache[key], cached.isFresh { return cached.value }

            let placemark = try await withTaskCancellationHandler {
                try await geocoder.reverseGeocode(loc, preferredLocale: Self.geocodingLocale)
            } onCancel: {
                CLGeocoder().cancelGeocode()
            }

            guard let countryCode = placemark?.isoCountryCode else { return nil }

            let normalize: (String) -> String = {
                $0.trimmingCharacters(in: .whitespacesAndNewlines)
                  .lowercased()
                  .folding(options: .diacriticInsensitive, locale: Self.geocodingLocale)
            }
            let normalizedAdmin = placemark?.administrativeArea.map(normalize)
            let normalizedCounty = placemark?.subAdministrativeArea
                .map(normalize)
                .map { $0.replacingOccurrences(of: Self.countySuffix, with: "") }

            let adminCode = RegionalAreas.matchSubdivision(
                country: countryCode, normalized: normalizedAdmin
            )
            let countyKey = RegionalAreas.matchCounty(
                country: countryCode, state: adminCode, normalized: normalizedCounty
            )

            let selection = RegionSelection(
                countryCode: countryCode,
                administrativeAreaCode: adminCode,
                countyKey: countyKey,
                source: .location
            )
            cache[key] = CachedResult(value: selection, expiresAt: Date().addingTimeInterval(Self.cacheTTL))
            return selection
        } catch {
            logger.debug("Region resolution failed: \(error)")
            return nil
        }
    }

    private struct CacheKey: Hashable {
        let lat: Int
        let lng: Int
        init(_ location: CLLocation) {
            self.lat = Int(location.coordinate.latitude.rounded())
            self.lng = Int(location.coordinate.longitude.rounded())
        }
    }

    private struct CachedResult {
        let value: RegionSelection
        let expiresAt: Date
        var isFresh: Bool { Date() < expiresAt }
    }
}

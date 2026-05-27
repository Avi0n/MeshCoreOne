import CoreLocation
import Foundation

/// Cache key + render descriptor for a map thumbnail. Hashable on
/// `(rounded lat/lon, isDark)`. Rounding to 5 decimal places (~1.1 m) stops
/// float jitter from sharding the cache; `isDark` keeps dark/light snapshots
/// distinct. Render size is a constant (`MapSnapshotLayout`), deliberately not
/// in the key.
struct MapSnapshotRequest: Hashable {
    let latitude: Double
    let longitude: Double
    let isDark: Bool

    private static let coordinatePrecision = 100_000.0

    init(latitude: Double, longitude: Double, isDark: Bool) {
        self.latitude = (latitude * Self.coordinatePrecision).rounded() / Self.coordinatePrecision
        self.longitude = (longitude * Self.coordinatePrecision).rounded() / Self.coordinatePrecision
        self.isDark = isDark
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// `NSString` key for the backing `NSCache`, mirroring `InlineImageCache`'s
    /// `url.absoluteString as NSString` pattern.
    var cacheKey: NSString {
        "\(latitude),\(longitude),\(isDark)" as NSString
    }
}

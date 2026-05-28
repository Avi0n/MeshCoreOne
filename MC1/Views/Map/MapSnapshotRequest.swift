import Foundation

/// Cache key + render descriptor for a map thumbnail. Hashable on
/// `(rounded lat/lon, isDark, isOffline)`. Rounding to 5 decimal places
/// (~1.1 m) stops float jitter from sharding the cache; `isDark` keeps
/// dark/light snapshots distinct; `isOffline` keeps the snapshotter's
/// offline-pack style URL keyed separately from the online style so a
/// pre-offline render does not satisfy a post-offline lookup (and vice
/// versa). Render size is a constant (`MapSnapshotLayout`), deliberately
/// not in the key.
struct MapSnapshotRequest: Hashable {
    let latitude: Double
    let longitude: Double
    let isDark: Bool
    let isOffline: Bool

    private static let coordinatePrecision = 100_000.0

    init(latitude: Double, longitude: Double, isDark: Bool, isOffline: Bool) {
        // Normalize -0.0 to 0.0. -0.0 and 0.0 are `Hashable`-equal (so they dedupe
        // as one request and share an index entry), but string interpolation in
        // `cacheKey` renders them "-0.0" vs "0.0" — two distinct NSCache slots.
        // Collapsing the sign keeps the cache key consistent with equality.
        let roundedLatitude = (latitude * Self.coordinatePrecision).rounded() / Self.coordinatePrecision
        let roundedLongitude = (longitude * Self.coordinatePrecision).rounded() / Self.coordinatePrecision
        self.latitude = roundedLatitude == 0 ? 0 : roundedLatitude
        self.longitude = roundedLongitude == 0 ? 0 : roundedLongitude
        self.isDark = isDark
        self.isOffline = isOffline
    }

    /// `NSString` key for the backing `NSCache`, mirroring `InlineImageCache`'s
    /// `url.absoluteString as NSString` pattern.
    var cacheKey: NSString {
        "\(latitude),\(longitude),\(isDark),\(isOffline)" as NSString
    }
}

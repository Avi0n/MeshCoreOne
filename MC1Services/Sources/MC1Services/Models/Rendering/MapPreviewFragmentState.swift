import CoreLocation
import Foundation

/// Display state for a chat map-location thumbnail. Lat/lon are stored as
/// `Double` (not `CLLocationCoordinate2D`, which is neither `Sendable` nor
/// `Hashable`) so the fragment stays `Sendable, Hashable`.
///
/// `isDark` is carried from the build so the view's `MapSnapshotRequest` key is
/// identical to the one the build used to compute `isReady`; the view must not
/// re-read `@Environment(\.colorScheme)`. `isOffline` is carried for the same
/// reason — it is part of the cache key, so the view must use the same value
/// the build used. `isReady` flips true once the snapshot for the current
/// `(rounded lat/lon, isDark, isOffline)` is resolved (cached or failed), which
/// changes this `Hashable` value and reloads the row.
public struct MapPreviewFragmentState: Sendable, Hashable {
  public let latitude: Double
  public let longitude: Double
  public let isDark: Bool
  public let isOffline: Bool
  public let isReady: Bool

  public init(latitude: Double, longitude: Double, isDark: Bool, isOffline: Bool, isReady: Bool) {
    self.latitude = latitude
    self.longitude = longitude
    self.isDark = isDark
    self.isOffline = isOffline
    self.isReady = isReady
  }

  public var coordinate: CLLocationCoordinate2D {
    CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
  }
}

import SwiftUI

/// Map style options for the Map tab
enum MapStyleSelection: String, CaseIterable, Hashable {
  case standard
  case satellite
  case topo

  var label: String {
    switch self {
    case .standard: L10n.Map.Map.Style.standard
    case .satellite: L10n.Map.Map.Style.satellite
    case .topo: L10n.Map.Map.Style.topo
    }
  }

  var requiresNetwork: Bool {
    switch self {
    case .standard: false
    case .satellite: true
    case .topo: false
    }
  }

  var offlineMapLayer: OfflineMapLayer {
    switch self {
    case .standard: .base
    case .satellite: .base
    case .topo: .topo
    }
  }

  /// All styles use the same base vector style; satellite/topo add raster overlays at runtime.
  /// When offline, always returns Liberty — offline packs are downloaded against that style
  /// and MapLibre serves cached tiles only for the exact style URL used during download.
  func styleURL(isDarkMode: Bool, isOffline: Bool = false) -> URL {
    let useDark = isDarkMode && !isOffline
    let url = useDark ? MapTileURLs.openFreeMapDark : MapTileURLs.openFreeMapLiberty
    guard let result = URL(string: url) else {
      fatalError("Invalid map tile URL constant: \(url)")
    }
    return result
  }
}

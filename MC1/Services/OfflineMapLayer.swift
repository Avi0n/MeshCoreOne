import Foundation

// swiftformat:disable redundantRawValues
enum OfflineMapLayer: String, Codable {
  // swiftlint:disable redundant_string_enum_value
  case base = "base"
  case topo = "topo"
  // swiftlint:enable redundant_string_enum_value

  var label: String {
    switch self {
    case .base: L10n.Settings.OfflineMaps.Layer.base
    case .topo: L10n.Settings.OfflineMaps.Layer.topo
    }
  }

  var maxDownloadZoom: Double {
    switch self {
    case .base: 14
    case .topo: 17
    }
  }

  var styleURL: URL? {
    switch self {
    case .base:
      URL(string: MapTileURLs.openFreeMapLiberty)
    case .topo:
      Bundle.main.url(forResource: "topo-offline", withExtension: "json")
    }
  }
}

// swiftformat:enable redundantRawValues

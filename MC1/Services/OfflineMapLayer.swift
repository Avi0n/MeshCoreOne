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

  var selectableMaximumZoom: ClosedRange<Int> {
    switch self {
    case .base: Self.minimumSelectableZoom...Self.baseMaximumZoom
    case .topo: Self.minimumSelectableZoom...Self.topographyMaximumZoom
    }
  }

  var defaultMaximumZoom: Int {
    selectableMaximumZoom.upperBound
  }

  private static let minimumSelectableZoom = 0
  private static let baseMaximumZoom = 14
  private static let topographyMaximumZoom = 16

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

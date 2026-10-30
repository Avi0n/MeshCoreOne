import Foundation

enum OfflineMapError: LocalizedError {
  case invalidDetail
  case insufficientDiskSpace
  case providerLimitExceeded
  case missingStyleResource(OfflineMapLayer)

  var errorDescription: String? {
    switch self {
    case .invalidDetail:
      L10n.Settings.OfflineMaps.Error.invalidDetail
    case .providerLimitExceeded:
      L10n.Settings.OfflineMaps.Error.providerLimitExceeded
    case .insufficientDiskSpace:
      L10n.Settings.OfflineMaps.storageRemediation
    case let .missingStyleResource(layer):
      "Missing style resource for layer: \(layer.rawValue)"
    }
  }
}

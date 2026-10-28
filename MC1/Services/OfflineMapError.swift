import Foundation

enum OfflineMapError: LocalizedError {
  case insufficientDiskSpace
  case providerLimitExceeded
  case missingStyleResource(OfflineMapLayer)

  var errorDescription: String? {
    switch self {
    case .providerLimitExceeded:
      L10n.Settings.OfflineMaps.Error.providerLimitExceeded
    case .insufficientDiskSpace:
      L10n.Settings.OfflineMaps.Error.insufficientDiskSpace
    case let .missingStyleResource(layer):
      "Missing style resource for layer: \(layer.rawValue)"
    }
  }
}

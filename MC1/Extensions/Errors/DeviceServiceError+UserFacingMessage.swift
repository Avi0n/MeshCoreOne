import MC1Services

extension DeviceServiceError {
  /// L10n-routed user-facing message for device service errors. The
  /// package-level `errorDescription` stays the developer-facing fallback.
  var userFacingMessage: String {
    switch self {
    case .deviceNotFound:
      L10n.Localizable.Error.DeviceService.deviceNotFound
    case let .persistenceFailed(reason):
      L10n.Localizable.Error.DeviceService.persistenceFailed(reason)
    }
  }
}

import MC1Services

extension RoomServerError {
  /// L10n-routed user-facing message for room server errors. The
  /// package-level `errorDescription` stays the developer-facing fallback.
  var userFacingMessage: String {
    switch self {
    case .notConnected:
      L10n.Localizable.Error.RoomServer.notConnected
    case .sessionNotFound:
      L10n.Localizable.Error.RoomServer.sessionNotFound
    case .sendFailed:
      L10n.Localizable.Error.RoomServer.sendFailed
    case .permissionDenied:
      L10n.Localizable.Error.RoomServer.permissionDenied
    case .invalidResponse:
      L10n.Localizable.Error.RoomServer.invalidResponse
    case let .sessionError(error):
      error.userFacingMessage
    }
  }
}

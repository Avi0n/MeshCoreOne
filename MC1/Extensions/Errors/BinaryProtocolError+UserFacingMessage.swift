import MC1Services

extension BinaryProtocolError {
  /// L10n-routed user-facing message for binary protocol errors. The
  /// package-level `errorDescription` stays the developer-facing fallback.
  var userFacingMessage: String {
    switch self {
    case .notConnected:
      L10n.Localizable.Error.BinaryProtocol.notConnected
    case .sendFailed:
      L10n.Localizable.Error.BinaryProtocol.sendFailed
    case .timeout:
      L10n.Localizable.Error.BinaryProtocol.timeout
    case .invalidResponse:
      L10n.Localizable.Error.BinaryProtocol.invalidResponse
    case let .sessionError(error):
      error.userFacingMessage
    }
  }
}

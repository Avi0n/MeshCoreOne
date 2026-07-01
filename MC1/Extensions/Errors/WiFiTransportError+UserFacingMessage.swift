import MC1Services

extension WiFiTransportError {
  /// L10n-routed user-facing message for WiFi transport errors. The
  /// package-level `errorDescription` stays the developer-facing fallback.
  var userFacingMessage: String {
    switch self {
    case let .connectionFailed(reason):
      L10n.Localizable.Error.Wifi.connectionFailed(reason)
    case .connectionTimeout:
      L10n.Localizable.Error.Wifi.connectionTimeout
    case .notConnected:
      L10n.Localizable.Error.Wifi.notConnected
    case let .sendFailed(reason):
      L10n.Localizable.Error.Wifi.sendFailed(reason)
    case .sendTimeout:
      L10n.Localizable.Error.Wifi.sendTimeout
    case .invalidHost:
      L10n.Localizable.Error.Wifi.invalidHost
    case .invalidPort:
      L10n.Localizable.Error.Wifi.invalidPort
    case .notConfigured:
      L10n.Localizable.Error.Wifi.notConfigured
    }
  }
}

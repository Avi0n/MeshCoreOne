import MC1Services

extension KeychainError {
  /// L10n-routed user-facing message for keychain errors. The
  /// package-level `errorDescription` stays the developer-facing fallback.
  var userFacingMessage: String {
    switch self {
    case .encodingFailed:
      L10n.Localizable.Error.Keychain.encodingFailed
    case let .storageFailed(status):
      L10n.Localizable.Error.Keychain.storageFailed(Int(status))
    case let .retrievalFailed(status):
      L10n.Localizable.Error.Keychain.retrievalFailed(Int(status))
    case let .deletionFailed(status):
      L10n.Localizable.Error.Keychain.deletionFailed(Int(status))
    }
  }
}

import MC1Services

extension StoreServiceError {
  /// L10n-routed user-facing message for store errors. The
  /// package-level `errorDescription` stays the developer-facing fallback.
  var userFacingMessage: String {
    switch self {
    case .productsNotLoaded:
      L10n.Settings.Support.Error.productsNotLoaded
    case .productNotFound:
      L10n.Settings.Support.Error.productNotFound
    case let .purchaseFailed(reason):
      L10n.Settings.Support.Error.purchaseFailed(reason)
    case .verificationFailed:
      L10n.Settings.Support.Error.verificationFailed
    case .notEntitled:
      L10n.Settings.Support.Error.notEntitled
    case .networkUnavailable:
      L10n.Settings.Support.Error.networkUnavailable
    case .storefrontUnavailable:
      L10n.Settings.Support.Error.storefrontUnavailable
    case .unsupported:
      L10n.Settings.Support.Error.unsupported
    }
  }
}

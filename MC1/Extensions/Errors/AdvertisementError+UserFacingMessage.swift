import MC1Services

extension AdvertisementError {
    /// L10n-routed user-facing message for advertisement errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.Advertisement.notConnected
        case .sendFailed:
            L10n.Localizable.Error.Advertisement.sendFailed
        case .invalidResponse:
            L10n.Localizable.Error.Advertisement.invalidResponse
        case .sessionError(let error):
            error.userFacingMessage
        }
    }
}

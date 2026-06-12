import MC1Services

extension MessagePollingError {
    /// L10n-routed user-facing message for message polling errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.MessagePolling.notConnected
        case .pollingFailed:
            L10n.Localizable.Error.MessagePolling.pollingFailed
        case .sessionError(let error):
            error.userFacingMessage
        }
    }
}

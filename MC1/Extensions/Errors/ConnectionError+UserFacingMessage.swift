import MC1Services

extension ConnectionError {
    /// L10n-routed user-facing message for connection lifecycle errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .connectionFailed(let reason):
            L10n.Localizable.Error.Connection.connectionFailed(reason)
        case .deviceNotFound:
            L10n.Localizable.Error.Connection.deviceNotFound
        case .notConnected:
            L10n.Localizable.Error.Connection.notConnected
        case .initializationFailed(let reason):
            L10n.Localizable.Error.Connection.initializationFailed(reason)
        }
    }
}

import MC1Services

extension RemoteNodeError {
    /// L10n-routed user-facing message for remote node errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.RemoteNode.notConnected
        case .loginFailed(let reason):
            L10n.Localizable.Error.RemoteNode.loginFailed(reason)
        case .sendFailed(let reason):
            L10n.Localizable.Error.RemoteNode.sendFailed(reason)
        case .invalidResponse:
            L10n.Localizable.Error.RemoteNode.invalidResponse
        case .permissionDenied:
            L10n.Localizable.Error.RemoteNode.permissionDenied
        case .timeout:
            L10n.Localizable.Error.RemoteNode.timeout
        case .sessionNotFound:
            L10n.Localizable.Error.RemoteNode.sessionNotFound
        case .passwordNotFound:
            L10n.Localizable.Error.RemoteNode.passwordNotFound
        case .floodRouted:
            L10n.Localizable.Error.RemoteNode.floodRouted
        case .pathDiscoveryFailed:
            L10n.Localizable.Error.RemoteNode.pathDiscoveryFailed
        case .contactNotFound:
            L10n.Localizable.Error.RemoteNode.contactNotFound
        case .cancelled:
            L10n.Localizable.Error.RemoteNode.cancelled
        case .sessionError(let error):
            error.userFacingMessage
        }
    }
}

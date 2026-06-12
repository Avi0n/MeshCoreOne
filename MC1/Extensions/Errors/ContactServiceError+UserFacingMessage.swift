import MC1Services

extension ContactServiceError {
    /// L10n-routed user-facing message for contact service errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.ContactService.notConnected
        case .sendFailed:
            L10n.Localizable.Error.ContactService.sendFailed
        case .invalidResponse:
            L10n.Localizable.Error.ContactService.invalidResponse
        case .syncInterrupted:
            L10n.Localizable.Error.ContactService.syncInterrupted
        case .contactNotFound:
            L10n.Localizable.Error.ContactService.contactNotFound
        case .contactTableFull:
            L10n.Localizable.Error.ContactService.contactTableFull
        case .shareContactUnavailable:
            L10n.Localizable.Error.ContactService.shareContactUnavailable
        case .sessionError(let error):
            error.userFacingMessage
        }
    }
}

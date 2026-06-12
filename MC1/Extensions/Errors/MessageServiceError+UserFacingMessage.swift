import MC1Services

extension MessageServiceError {
    /// L10n-routed user-facing message for message service errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.MessageService.notConnected
        case .contactNotFound:
            L10n.Localizable.Error.MessageService.contactNotFound
        case .channelNotFound:
            L10n.Localizable.Error.MessageService.channelNotFound
        case .sendFailed(let reason):
            L10n.Localizable.Error.MessageService.sendFailed(reason)
        case .invalidRecipient:
            L10n.Localizable.Error.MessageService.invalidRecipient
        case .messageTooLong:
            L10n.Localizable.Error.MessageService.messageTooLong
        case .sessionError(let error):
            error.userFacingMessage
        }
    }
}

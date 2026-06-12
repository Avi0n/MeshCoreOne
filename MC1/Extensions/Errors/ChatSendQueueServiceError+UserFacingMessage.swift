import MC1Services

extension ChatSendQueueServiceError {
    /// L10n-routed user-facing message for send queue errors. The wrapped
    /// error in `persistFailed` recurses through `userFacingMessage` so
    /// mapped service errors localize instead of falling back to English.
    var userFacingMessage: String {
        switch self {
        case .persistFailed(let underlying):
            L10n.Localizable.Error.ChatSendQueue.persistFailed(underlying.userFacingMessage)
        case .notConnected:
            L10n.Localizable.Error.ChatSendQueue.notConnected
        }
    }
}

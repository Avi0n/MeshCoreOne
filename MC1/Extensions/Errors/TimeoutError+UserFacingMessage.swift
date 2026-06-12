import MC1Services

extension TimeoutError {
    /// Generic localized timeout message. `operationName` is a `#function`
    /// string, developer-facing by construction, so it stays out of the
    /// user-facing rendering.
    var userFacingMessage: String {
        L10n.Localizable.Error.Timeout.operationTimedOut
    }
}

import MC1Services

extension SettingsServiceError {
    /// L10n-routed user-facing message for settings service errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.Settings.notConnected
        case .sendFailed:
            L10n.Localizable.Error.Settings.sendFailed
        case .invalidResponse:
            L10n.Localizable.Error.Settings.invalidResponse
        case .sessionError(let error):
            error.userFacingMessage
        case .verificationFailed(let expected, let actual):
            L10n.Localizable.Error.Settings.verificationFailed(expected, actual)
        case .deviceGPSVerificationFailed(let expectedEnabled, _):
            // Two whole-sentence variants instead of interpolating an On/Off word,
            // so every locale can phrase the toggle state naturally.
            expectedEnabled
                ? L10n.Localizable.Error.Settings.gpsNotSavedExpectedOn
                : L10n.Localizable.Error.Settings.gpsNotSavedExpectedOff
        }
    }
}

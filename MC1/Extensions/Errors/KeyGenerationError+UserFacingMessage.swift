import MC1Services

extension KeyGenerationError {
    /// L10n-routed user-facing message for key generation errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .maxAttemptsExceeded:
            L10n.Localizable.Error.KeyGeneration.maxAttemptsExceeded
        case .reservedPrefix:
            L10n.Localizable.Error.KeyGeneration.reservedPrefix
        case .randomGenerationFailed:
            L10n.Localizable.Error.KeyGeneration.randomGenerationFailed
        case .invalidKey:
            L10n.Localizable.Error.KeyGeneration.invalidKey
        }
    }
}

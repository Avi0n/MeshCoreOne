import MC1Services

extension PersistenceStoreError {
    /// L10n-routed user-facing message for persistence errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .deviceNotFound:
            L10n.Localizable.Error.Persistence.deviceNotFound
        case .contactNotFound:
            L10n.Localizable.Error.Persistence.contactNotFound
        case .messageNotFound:
            L10n.Localizable.Error.Persistence.messageNotFound
        case .channelNotFound:
            L10n.Localizable.Error.Persistence.channelNotFound
        case .remoteNodeSessionNotFound:
            L10n.Localizable.Error.Persistence.remoteNodeSessionNotFound
        case .saveFailed(let reason):
            L10n.Localizable.Error.Persistence.saveFailed(reason)
        case .fetchFailed(let reason):
            L10n.Localizable.Error.Persistence.fetchFailed(reason)
        case .invalidData:
            L10n.Localizable.Error.Persistence.invalidData
        }
    }
}

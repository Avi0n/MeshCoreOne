import MC1Services

extension SyncCoordinatorError {
    /// L10n-routed user-facing message for sync coordinator errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.SyncCoordinator.notConnected
        case .syncFailed(let reason):
            L10n.Localizable.Error.SyncCoordinator.syncFailed(reason)
        case .alreadySyncing:
            L10n.Localizable.Error.SyncCoordinator.alreadySyncing
        }
    }
}

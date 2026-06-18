import MC1Services

extension ChannelServiceError {
    /// L10n-routed user-facing message for channel service errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .notConnected:
            L10n.Localizable.Error.ChannelService.notConnected
        case .channelNotFound:
            L10n.Localizable.Error.ChannelService.channelNotFound
        case .invalidChannelIndex:
            L10n.Localizable.Error.ChannelService.invalidChannelIndex
        case .secretHashingFailed:
            L10n.Localizable.Error.ChannelService.secretHashingFailed
        case .saveFailed(let reason):
            L10n.Localizable.Error.ChannelService.saveFailed(reason)
        case .sendFailed(let reason):
            L10n.Localizable.Error.ChannelService.sendFailed(reason)
        case .sessionError(let error):
            error.userFacingMessage
        case .syncAlreadyInProgress:
            L10n.Localizable.Error.ChannelService.syncAlreadyInProgress
        case .circuitBreakerOpen(let consecutiveFailures):
            L10n.Localizable.Error.ChannelService.circuitBreakerOpen(consecutiveFailures)
        }
    }
}

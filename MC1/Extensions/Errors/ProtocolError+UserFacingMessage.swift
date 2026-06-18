import MC1Services

extension ProtocolError {
    /// L10n-routed user-facing message for raw firmware error codes. Shares the
    /// `error.device.*` keys with `MeshCoreError.deviceError(code:)` because both
    /// describe the identical firmware codes.
    var userFacingMessage: String {
        switch self {
        case .unsupportedCommand: L10n.Localizable.Error.Device.unsupportedCommand
        case .notFound: L10n.Localizable.Error.Device.notFound
        case .tableFull: L10n.Localizable.Error.Device.storageFull
        case .badState: L10n.Localizable.Error.Device.invalidState
        case .fileIOError: L10n.Localizable.Error.Device.fileSystem
        case .illegalArgument: L10n.Localizable.Error.Device.invalidParameter
        }
    }
}

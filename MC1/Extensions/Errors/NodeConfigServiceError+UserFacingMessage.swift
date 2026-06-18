import MC1Services

extension NodeConfigServiceError {
    /// L10n-routed user-facing message for config import validation errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .invalidChannelSecret(let index, let hexLength):
            L10n.Settings.ConfigImport.Error.invalidChannelSecret(index, hexLength)
        case .invalidContactPublicKey(let name):
            L10n.Settings.ConfigImport.Error.invalidContactPublicKey(name)
        case .invalidPathHashMode(let name, let mode):
            L10n.Settings.ConfigImport.Error.invalidPathHashMode(name, Int(mode))
        case .invalidPrivateKey(let hexLength):
            L10n.Settings.ConfigImport.Error.invalidPrivateKey(hexLength, ProtocolLimits.privateKeySize * 2)
        case .invalidRadioSettings(let field):
            L10n.Settings.ConfigImport.Error.radioOutOfRange(Self.radioFieldLabel(field))
        case .noAvailableChannelSlot(let name):
            L10n.Settings.ConfigImport.Error.noAvailableChannelSlot(name)
        case .invalidCoordinate(let field):
            switch field {
            case .positionLatitude, .positionLongitude:
                L10n.Settings.ConfigImport.Error.positionInvalid(Self.coordinateLabel(field))
            case .contactLatitude(let name), .contactLongitude(let name):
                L10n.Settings.ConfigImport.Error.contactCoordinateInvalid(name, Self.coordinateLabel(field))
            }
        case .invalidOutPath(let name):
            L10n.Settings.ConfigImport.Error.invalidOutPath(name)
        case .contactCapacityExceeded(let needed, let available):
            L10n.Settings.ConfigImport.Error.contactCapacityExceeded(needed, available)
        }
    }

    private static func radioFieldLabel(_ field: RadioField) -> String {
        switch field {
        case .frequency: L10n.Settings.ConfigImport.Field.frequency
        case .bandwidth: L10n.Settings.ConfigImport.Field.bandwidth
        case .spreadingFactor: L10n.Settings.ConfigImport.Field.spreadingFactor
        case .codingRate: L10n.Settings.ConfigImport.Field.codingRate
        case .txPower: L10n.Settings.ConfigImport.Field.txPower
        }
    }

    private static func coordinateLabel(_ field: CoordinateField) -> String {
        switch field {
        case .positionLatitude, .contactLatitude: L10n.Settings.ConfigImport.Field.latitude
        case .positionLongitude, .contactLongitude: L10n.Settings.ConfigImport.Field.longitude
        }
    }
}

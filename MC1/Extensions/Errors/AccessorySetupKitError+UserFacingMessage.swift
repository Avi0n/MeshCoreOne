import MC1Services

extension AccessorySetupKitError {
  /// L10n-routed user-facing message for accessory pairing errors. The
  /// package-level `errorDescription` stays the developer-facing fallback.
  var userFacingMessage: String {
    switch self {
    case .sessionNotActive:
      L10n.Localizable.Error.AccessorySetup.sessionNotActive
    case .sessionInvalidated:
      L10n.Localizable.Error.AccessorySetup.sessionInvalidated
    case .pickerDismissed:
      L10n.Localizable.Error.AccessorySetup.pickerDismissed
    case .pickerRestricted:
      L10n.Localizable.Error.AccessorySetup.pickerRestricted
    case .pickerAlreadyActive:
      L10n.Localizable.Error.AccessorySetup.pickerAlreadyActive
    case let .pairingFailed(reason):
      L10n.Localizable.Error.AccessorySetup.pairingFailed(reason)
    case .noBluetoothIdentifier:
      L10n.Localizable.Error.AccessorySetup.noBluetoothIdentifier
    case .discoveryTimeout:
      L10n.Localizable.Error.AccessorySetup.discoveryTimeout
    case .connectionFailed:
      L10n.Localizable.Error.AccessorySetup.connectionFailed
    }
  }
}

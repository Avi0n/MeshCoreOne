import MC1Services

extension BLEError {
    /// L10n-routed user-facing message for BLE transport errors. The
    /// package-level `errorDescription` stays the developer-facing fallback.
    var userFacingMessage: String {
        switch self {
        case .bluetoothUnavailable:
            L10n.Localizable.Error.Ble.bluetoothUnavailable
        case .bluetoothUnauthorized:
            L10n.Localizable.Error.Ble.bluetoothUnauthorized
        case .bluetoothPoweredOff:
            L10n.Localizable.Error.Ble.bluetoothPoweredOff
        case .deviceNotFound:
            L10n.Localizable.Error.Ble.deviceNotFound
        case .connectionFailed(let message):
            L10n.Localizable.Error.Ble.connectionFailed(message)
        case .connectionTimeout:
            L10n.Localizable.Error.Ble.connectionTimeout
        case .notConnected:
            L10n.Localizable.Error.Ble.notConnected
        case .characteristicNotFound:
            L10n.Localizable.Error.Ble.characteristicNotFound
        case .writeError(let message):
            L10n.Localizable.Error.Ble.writeError(message)
        case .invalidResponse:
            L10n.Localizable.Error.Ble.invalidResponse
        case .operationTimeout:
            L10n.Localizable.Error.Ble.operationTimeout
        case .authenticationFailed:
            L10n.Localizable.Error.Ble.authenticationFailed
        case .pairingFailed(let reason):
            L10n.Localizable.Error.Ble.pairingFailed(reason)
        case .deviceConnectedToOtherApp:
            L10n.Localizable.Error.Ble.deviceConnectedToOtherApp
        }
    }
}

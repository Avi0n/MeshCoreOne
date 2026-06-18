import Foundation

/// Errors that can occur during device pairing
public enum PairingError: LocalizedError {
    /// ASK pairing succeeded but BLE connection failed (e.g., wrong PIN)
    case connectionFailed(deviceID: UUID, underlying: Error)
    /// ASK pairing succeeded but device is connected to another app
    case deviceConnectedToOtherApp(deviceID: UUID)

    public var errorDescription: String? {
        switch self {
        case .connectionFailed(_, let underlying):
            return "Connection failed: \(underlying.localizedDescription)"
        case .deviceConnectedToOtherApp:
            return "Device is connected to another app."
        }
    }

    /// The device ID that failed to connect (for recovery UI)
    public var deviceID: UUID? {
        switch self {
        case .connectionFailed(let deviceID, _):
            return deviceID
        case .deviceConnectedToOtherApp(let deviceID):
            return deviceID
        }
    }

    /// True when the underlying BLE failure is an auth/encryption error.
    /// Detection is locale-independent: BLEStateMachine maps CBATTError auth
    /// codes (5/8/12/15) and CBError.encryptionTimedOut to BLEError.authenticationFailed
    /// at the throw site.
    public var isAuthenticationFailure: Bool {
        guard case .connectionFailed(_, let underlying) = self else { return false }
        if case BLEError.authenticationFailed = underlying { return true }
        return false
    }
}

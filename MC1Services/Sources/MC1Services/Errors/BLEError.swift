import Foundation

// MARK: - BLE Errors

/// Errors that can occur during BLE operations
public enum BLEError: Error, Sendable {
    case bluetoothUnavailable
    case bluetoothUnauthorized
    case bluetoothPoweredOff
    case deviceNotFound
    case connectionFailed(String)
    case connectionTimeout
    case notConnected
    case characteristicNotFound
    case writeError(String)
    case invalidResponse
    case operationTimeout
    case authenticationFailed
    case pairingFailed(String)
    case deviceConnectedToOtherApp
}

// MARK: - BLEError LocalizedError Conformance

extension BLEError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .bluetoothUnavailable:
            return "Bluetooth is not available on this device."
        case .bluetoothUnauthorized:
            return "Bluetooth permission is required. Please enable it in Settings."
        case .bluetoothPoweredOff:
            return "Bluetooth is turned off. Please enable Bluetooth to connect."
        case .deviceNotFound:
            return "Device not found. Please make sure it's powered on and nearby."
        case .connectionFailed(let message):
            return "Connection failed: \(message)"
        case .connectionTimeout:
            return "Connection timed out. Please try again."
        case .notConnected:
            return "Not connected to a device."
        case .characteristicNotFound:
            return "Unable to communicate with device. Please try reconnecting."
        case .writeError(let message):
            return "Failed to send data: \(message)"
        case .invalidResponse:
            return "Invalid response from device. Please try again."
        case .operationTimeout:
            return "Operation timed out. Please try again."
        case .authenticationFailed:
            return "Authentication failed. Please check your device's PIN."
        case .pairingFailed(let reason):
            return "Bluetooth pairing failed: \(reason)"
        case .deviceConnectedToOtherApp:
            return "This device is connected to another app. Only one app can use a mesh radio at a time to prevent communication issues."
        }
    }
}

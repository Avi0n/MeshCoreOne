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
      "Bluetooth is not available on this device."
    case .bluetoothUnauthorized:
      "Bluetooth permission is required. Please enable it in Settings."
    case .bluetoothPoweredOff:
      "Bluetooth is turned off. Please enable Bluetooth to connect."
    case .deviceNotFound:
      "Device not found. Please make sure it's powered on and nearby."
    case let .connectionFailed(message):
      "Connection failed: \(message)"
    case .connectionTimeout:
      "Connection timed out. Please try again."
    case .notConnected:
      "Not connected to a device."
    case .characteristicNotFound:
      "Unable to communicate with device. Please try reconnecting."
    case let .writeError(message):
      "Failed to send data: \(message)"
    case .invalidResponse:
      "Invalid response from device. Please try again."
    case .operationTimeout:
      "Operation timed out. Please try again."
    case .authenticationFailed:
      "Authentication failed. Please check your device's PIN."
    case let .pairingFailed(reason):
      "Bluetooth pairing failed: \(reason)"
    case .deviceConnectedToOtherApp:
      "This device is connected to another app. Only one app can use a mesh radio at a time to prevent communication issues."
    }
  }
}

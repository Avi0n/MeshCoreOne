import Foundation
import MeshCore

// MARK: - MeshCoreError LocalizedError Conformance

extension MeshCoreError: @retroactive LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .timeout:
      "The operation timed out. Please try again."
    case let .deviceError(code):
      Self.deviceErrorDescription(code: code)
    case let .parseError(detail):
      "Failed to parse device response: \(detail)"
    case .notConnected:
      "Not connected to device."
    case let .commandFailed(_, reason):
      "Command failed: \(reason)"
    case let .invalidResponse(expected, got):
      "Unexpected response from device (expected \(expected), got \(got))."
    case .contactNotFound:
      "Contact not found on device."
    case let .dataTooLarge(maxSize, actualSize):
      "Data too large (\(actualSize) bytes, maximum is \(maxSize))."
    case let .signingFailed(reason):
      "Signing failed: \(reason)"
    case let .invalidInput(detail):
      "Invalid input: \(detail)"
    case let .unknown(detail):
      "An unknown error occurred: \(detail)"
    case .bluetoothUnavailable:
      "Bluetooth is not available on this device."
    case .bluetoothUnauthorized:
      "Bluetooth permission is required. Please enable it in Settings."
    case .bluetoothPoweredOff:
      "Bluetooth is turned off. Please enable Bluetooth to connect."
    case let .connectionLost(underlying):
      if let underlying {
        "Connection to device was lost: \(underlying.localizedDescription)"
      } else {
        "Connection to device was lost."
      }
    case .sessionNotStarted:
      "Session has not been started."
    case .featureDisabled:
      "This feature is disabled on the device."
    }
  }

  private static func deviceErrorDescription(code: UInt8) -> String {
    switch code {
    case 0x01: "Command not supported by device firmware."
    case 0x02: "Item not found on device."
    case 0x03: "Device storage is full."
    case 0x04: "Device is in an invalid state for this operation."
    case 0x05: "Device file system error."
    case 0x06: "Invalid parameter sent to device."
    default: "Device error (code \(code))."
    }
  }
}

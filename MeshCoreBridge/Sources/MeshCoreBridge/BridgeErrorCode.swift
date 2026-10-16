import Foundation
import MeshCore

/// Stable integer error codes delivered through `BridgeResultListener.onFailure`.
///
/// Kotlin mirrors these values exactly, so the raw integers are frozen: never
/// renumber an existing case, only append. The human-readable `message` on the
/// failure carries the underlying detail (including the firmware sub-code for
/// `deviceError`); this code is for programmatic branching.
public enum BridgeErrorCode: Int32, Sendable {
  /// A failure that does not map to any known category.
  case unknown = 0
  /// The operation timed out waiting for the device.
  case timeout = 1
  /// The device returned a `RESP_CODE_ERR` frame. The message carries the
  /// firmware sub-code (see `MeshCore.ErrorCode`).
  case deviceError = 2
  /// A device response could not be parsed.
  case parseError = 3
  /// The session/transport was not connected.
  case notConnected = 4
  /// A command failed on the device.
  case commandFailed = 5
  /// The device returned a response of an unexpected shape.
  case invalidResponse = 6
  /// The referenced contact was not found.
  case contactNotFound = 7
  /// The payload exceeded the device's maximum size.
  case dataTooLarge = 8
  /// Cryptographic signing failed.
  case signingFailed = 9
  /// A caller-supplied argument was invalid.
  case invalidInput = 10
  /// Bluetooth is unavailable on this device.
  case bluetoothUnavailable = 11
  /// The app is not authorized to use Bluetooth.
  case bluetoothUnauthorized = 12
  /// Bluetooth is powered off.
  case bluetoothPoweredOff = 13
  /// The connection was lost mid-operation.
  case connectionLost = 14
  /// The session was not started.
  case sessionNotStarted = 15
  /// The requested feature is disabled on the device.
  case featureDisabled = 16
  /// The transport layer failed to connect.
  case transportConnectionFailed = 17
  /// The transport layer failed to send.
  case transportSendFailed = 18
  /// The target device could not be found by the transport.
  case transportDeviceNotFound = 19
  /// A required BLE service was not found.
  case transportServiceNotFound = 20
  /// A required BLE characteristic was not found.
  case transportCharacteristicNotFound = 21
  /// A destination public key or hex string was malformed.
  case invalidDestination = 22
}

extension BridgeErrorCode {
  /// Classifies any error thrown across the boundary into a stable code plus a
  /// diagnostic message. Unrecognized errors fall through to `.unknown` with the
  /// localized description so nothing is silently swallowed.
  static func classify(_ error: Error) -> (code: BridgeErrorCode, message: String) {
    if let error = error as? MeshCoreError {
      return classify(error)
    }
    if let error = error as? MeshTransportError {
      return classify(error)
    }
    if let error = error as? DestinationError {
      return (.invalidDestination, String(describing: error))
    }
    return (.unknown, error.localizedDescription)
  }

  private static func classify(_ error: MeshCoreError) -> (code: BridgeErrorCode, message: String) {
    switch error {
    case .timeout:
      return (.timeout, "operation timed out")
    case let .deviceError(code):
      let named = ErrorCode(rawValue: code).map { " (\($0))" } ?? ""
      return (.deviceError, "device error code=\(code)\(named)")
    case let .parseError(reason):
      return (.parseError, reason)
    case .notConnected:
      return (.notConnected, "not connected")
    case let .commandFailed(command, reason):
      return (.commandFailed, "command \(command) failed: \(reason)")
    case let .invalidResponse(expected, got):
      return (.invalidResponse, "expected \(expected), got \(got)")
    case let .contactNotFound(prefix):
      return (.contactNotFound, "contact not found: \(prefix.hexString)")
    case let .dataTooLarge(maxSize, actualSize):
      return (.dataTooLarge, "data too large: \(actualSize) > \(maxSize)")
    case let .signingFailed(reason):
      return (.signingFailed, reason)
    case let .invalidInput(detail):
      return (.invalidInput, detail)
    case .bluetoothUnavailable:
      return (.bluetoothUnavailable, "bluetooth unavailable")
    case .bluetoothUnauthorized:
      return (.bluetoothUnauthorized, "bluetooth unauthorized")
    case .bluetoothPoweredOff:
      return (.bluetoothPoweredOff, "bluetooth powered off")
    case let .connectionLost(underlying):
      return (.connectionLost, underlying.map { "connection lost: \($0.localizedDescription)" } ?? "connection lost")
    case .sessionNotStarted:
      return (.sessionNotStarted, "session not started")
    case .featureDisabled:
      return (.featureDisabled, "feature disabled")
    case let .unknown(detail):
      return (.unknown, detail)
    }
  }

  private static func classify(_ error: MeshTransportError) -> (code: BridgeErrorCode, message: String) {
    switch error {
    case .notConnected:
      return (.notConnected, "transport not connected")
    case let .connectionFailed(reason):
      return (.transportConnectionFailed, reason)
    case let .sendFailed(reason):
      return (.transportSendFailed, reason)
    case .deviceNotFound:
      return (.transportDeviceNotFound, "device not found")
    case .serviceNotFound:
      return (.transportServiceNotFound, "service not found")
    case .characteristicNotFound:
      return (.transportCharacteristicNotFound, "characteristic not found")
    }
  }
}

import Foundation

/// Errors that can occur during device pairing
public enum PairingError: LocalizedError {
  /// ASK pairing succeeded but BLE connection failed (e.g., wrong PIN)
  case connectionFailed(deviceID: UUID, underlying: Error)
  /// ASK pairing succeeded but device is connected to another app
  case deviceConnectedToOtherApp(deviceID: UUID)

  public var errorDescription: String? {
    switch self {
    case let .connectionFailed(_, underlying):
      "Connection failed: \(underlying.localizedDescription)"
    case .deviceConnectedToOtherApp:
      "Device is connected to another app."
    }
  }

  /// The device ID that failed to connect (for recovery UI)
  public var deviceID: UUID? {
    switch self {
    case let .connectionFailed(deviceID, _):
      deviceID
    case let .deviceConnectedToOtherApp(deviceID):
      deviceID
    }
  }

  /// True when the underlying BLE failure is an auth/encryption error.
  /// Detection is locale-independent: `ReconnectPolicy.makeConnectionError` is
  /// the single source of truth for which CoreBluetooth codes map to
  /// `BLEError.authenticationFailed` at the throw site.
  public var isAuthenticationFailure: Bool {
    guard case let .connectionFailed(_, underlying) = self else { return false }
    if case BLEError.authenticationFailed = underlying { return true }
    return false
  }
}

import Foundation

/// Represents errors that can occur at the transport layer.
///
/// These errors indicate problems with the underlying transport connection
/// (e.g., Bluetooth LE), rather than protocol-level errors.
public enum MeshTransportError: Error, Sendable, Equatable {
  /// Indicates the transport is not connected.
  case notConnected
  /// Indicates a connection attempt failed with a specific reason.
  case connectionFailed(String)
  /// Indicates sending data failed with a specific reason.
  case sendFailed(String)
  /// Indicates the target device could not be found.
  case deviceNotFound
  /// Indicates a required service was not found on the device.
  case serviceNotFound
  /// Indicates a required characteristic was not found on the device.
  case characteristicNotFound
}

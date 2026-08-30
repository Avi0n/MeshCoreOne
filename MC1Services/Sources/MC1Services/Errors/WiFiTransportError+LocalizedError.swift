import Foundation
import MeshCore

// MARK: - WiFiTransportError LocalizedError Conformance

extension WiFiTransportError: @retroactive LocalizedError {
  public var errorDescription: String? {
    switch self {
    case let .connectionFailed(reason):
      "Connection failed: \(reason)"
    case .connectionTimeout:
      "Connection timed out. Check the hostname or IP address and ensure the device is reachable."
    case .notConnected:
      "Not connected to device."
    case let .sendFailed(reason):
      "Failed to send data: \(reason)"
    case .sendTimeout:
      "Send operation timed out."
    case .invalidHost:
      "Invalid hostname or IP address."
    case .invalidPort:
      "Invalid port number."
    case .notConfigured:
      "Connection not configured."
    }
  }
}

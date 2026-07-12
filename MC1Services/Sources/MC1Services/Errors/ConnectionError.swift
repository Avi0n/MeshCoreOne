import Foundation

/// Errors that can occur during connection operations
public enum ConnectionError: LocalizedError {
  case connectionFailed(String)
  case deviceNotFound
  case notConnected
  case initializationFailed(String)

  public var errorDescription: String? {
    switch self {
    case let .connectionFailed(reason):
      "Connection failed: \(reason)"
    case .deviceNotFound:
      "Device not found"
    case .notConnected:
      "Not connected to device"
    case let .initializationFailed(reason):
      "Device initialization failed: \(reason)"
    }
  }
}

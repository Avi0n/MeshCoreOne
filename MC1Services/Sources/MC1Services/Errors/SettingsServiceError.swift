import Foundation
import MeshCore

public enum SettingsServiceError: Error, LocalizedError, Sendable {
  case notConnected
  case sendFailed
  case invalidResponse
  case sessionError(MeshCoreError)
  case verificationFailed(expected: String, actual: String)
  case deviceGPSVerificationFailed(expectedEnabled: Bool, actualEnabled: Bool)

  public var errorDescription: String? {
    switch self {
    case .notConnected: return "Device not connected"
    case .sendFailed: return "Failed to send command"
    case .invalidResponse: return "Invalid response from device"
    case let .sessionError(error): return error.localizedDescription
    case let .verificationFailed(expected, actual):
      return "Setting was not saved. Expected '\(expected)' but device reports '\(actual)'."
    case let .deviceGPSVerificationFailed(expectedEnabled, actualEnabled):
      let expected = expectedEnabled ? "On" : "Off"
      let actual = actualEnabled ? "On" : "Off"
      return "Device GPS setting was not saved. Expected '\(expected)' but device reports '\(actual)'."
    }
  }

  /// Whether this error suggests a connection issue that might be resolved by retrying
  public var isRetryable: Bool {
    switch self {
    case .sendFailed, .notConnected:
      return true
    case let .sessionError(error):
      if case .timeout = error { return true }
      return false
    default:
      return false
    }
  }
}

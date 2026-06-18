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
        case .sessionError(let error): return error.localizedDescription
        case .verificationFailed(let expected, let actual):
            return "Setting was not saved. Expected '\(expected)' but device reports '\(actual)'."
        case .deviceGPSVerificationFailed(let expectedEnabled, let actualEnabled):
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
        case .sessionError(let error):
            if case .timeout = error { return true }
            return false
        default:
            return false
        }
    }
}

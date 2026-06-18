import Foundation
import MeshCore

public enum RemoteNodeError: Error, LocalizedError, Sendable {
    case notConnected
    case loginFailed(String)
    case sendFailed(String)
    case invalidResponse
    case permissionDenied
    case timeout
    case sessionNotFound
    case passwordNotFound
    case floodRouted  // Keep-alive requires direct path
    case pathDiscoveryFailed
    case contactNotFound
    case cancelled  // Login cancelled due to duplicate attempt or shutdown
    case sessionError(MeshCoreError)

    public var errorDescription: String? {
        switch self {
        case .notConnected:
            return "Not connected to mesh device"
        case .loginFailed(let reason):
            return "Login failed: \(reason)"
        case .sendFailed(let reason):
            return "Failed to send: \(reason)"
        case .invalidResponse:
            return "Invalid response from remote node"
        case .permissionDenied:
            return "Permission denied"
        case .timeout:
            return "Request timed out"
        case .sessionNotFound:
            return "Remote node session not found"
        case .passwordNotFound:
            return "Password not found in keychain"
        case .floodRouted:
            return "Keep-alive requires direct routing path"
        case .pathDiscoveryFailed:
            return "Failed to establish direct path"
        case .contactNotFound:
            return "Contact not found in database"
        case .cancelled:
            return "Login cancelled"
        case .sessionError(let error):
            return error.localizedDescription
        }
    }

    public var isRetryable: Bool {
        switch self {
        case .timeout, .notConnected, .floodRouted:
            return true
        default:
            return false
        }
    }
}

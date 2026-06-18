import Foundation

/// Errors that can occur during connection operations
public enum ConnectionError: LocalizedError {
    case connectionFailed(String)
    case deviceNotFound
    case notConnected
    case initializationFailed(String)

    public var errorDescription: String? {
        switch self {
        case .connectionFailed(let reason):
            return "Connection failed: \(reason)"
        case .deviceNotFound:
            return "Device not found"
        case .notConnected:
            return "Not connected to device"
        case .initializationFailed(let reason):
            return "Device initialization failed: \(reason)"
        }
    }
}

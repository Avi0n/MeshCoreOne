// MC1Services/Sources/MC1Services/Models/DecryptStatus.swift
import Foundation

/// Decryption outcome for channel and direct messages.
public enum DecryptStatus: Int, Codable, Sendable, CaseIterable {
    case notApplicable = 0   // Not a channel message (e.g., direct, advert)
    case noMatchingKey = 1   // Channel: no stored channel matches
    case hmacFailed = 2      // Key found but HMAC validation failed
    case decryptFailed = 3   // HMAC passed but AES decrypt failed
    case success = 4         // Decrypted successfully
    case pending = 5         // Key found but decryption not yet implemented
    case dmNoMatchingKey = 6 // DM: missing private key or contact public key

    /// Developer-facing English description for logs; UI uses the app target's localized `DecryptStatus.localizedName`.
    public var displayName: String {
        switch self {
        case .notApplicable: return "N/A"
        case .noMatchingKey: return "No Key"
        case .hmacFailed: return "HMAC Failed"
        case .decryptFailed: return "Decrypt Failed"
        case .success: return "Decrypted"
        case .pending: return "Has Key"
        case .dmNoMatchingKey: return "No DM Key"
        }
    }

    /// SF Symbol name for status indicator.
    public var symbolName: String {
        switch self {
        case .notApplicable: return "minus.circle"
        case .noMatchingKey: return "key.slash"
        case .hmacFailed: return "exclamationmark.shield"
        case .decryptFailed: return "lock.slash"
        case .success: return "checkmark.seal"
        case .pending: return "key"
        case .dmNoMatchingKey: return "key.slash"
        }
    }
}

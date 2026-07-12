// MC1Services/Sources/MC1Services/Models/DecryptStatus.swift
import Foundation

/// Decryption outcome for channel and direct messages.
public enum DecryptStatus: Int, Codable, Sendable, CaseIterable {
  case notApplicable = 0 // Not a channel message (e.g., direct, advert)
  case noMatchingKey = 1 // Channel: no stored channel matches
  case hmacFailed = 2 // Key found but HMAC validation failed
  case decryptFailed = 3 // HMAC passed but AES decrypt failed
  case success = 4 // Decrypted successfully
  case pending = 5 // Key found but decryption not yet implemented
  case dmNoMatchingKey = 6 // DM: missing private key or contact public key

  /// Developer-facing English description for logs; UI uses the app target's localized `DecryptStatus.localizedName`.
  public var displayName: String {
    switch self {
    case .notApplicable: "N/A"
    case .noMatchingKey: "No Key"
    case .hmacFailed: "HMAC Failed"
    case .decryptFailed: "Decrypt Failed"
    case .success: "Decrypted"
    case .pending: "Has Key"
    case .dmNoMatchingKey: "No DM Key"
    }
  }

  /// SF Symbol name for status indicator.
  public var symbolName: String {
    switch self {
    case .notApplicable: "minus.circle"
    case .noMatchingKey: "key.slash"
    case .hmacFailed: "exclamationmark.shield"
    case .decryptFailed: "lock.slash"
    case .success: "checkmark.seal"
    case .pending: "key"
    case .dmNoMatchingKey: "key.slash"
    }
  }
}

import Foundation
import os
import Security

// MARK: - KeychainService

/// Secure password storage for remote node authentication.
/// Passwords are stored device-only (not synced to iCloud).
public actor KeychainService {
    public static let shared = KeychainService()

    private let service = "com.pocketmesh.nodepasswords"
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "Keychain")
    private let maxRetries = 3
    private let retryDelay: Duration = .milliseconds(100)

    public init() {}

    /// Store a password for a remote node.
    /// - Parameters:
    ///   - password: The password to store
    ///   - publicKey: The 32-byte public key of the remote node
    public func storePassword(_ password: String, forNodeKey publicKey: Data) async throws {
        let account = publicKey.base64EncodedString()
        guard let passwordData = password.data(using: .utf8) else {
            throw KeychainError.encodingFailed
        }

        // Delete existing entry first
        let deleteQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(deleteQuery as CFDictionary)

        // Add new entry
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: passwordData,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]

        var lastStatus: OSStatus = errSecSuccess
        for attempt in 1...maxRetries {
            lastStatus = SecItemAdd(addQuery as CFDictionary, nil)
            if lastStatus == errSecSuccess {
                logger.debug("Password stored for node")
                return
            }
            if attempt < maxRetries {
                try await Task.sleep(for: retryDelay)
            }
        }

        throw KeychainError.storageFailed(lastStatus)
    }

    /// Retrieve a stored password for a remote node.
    /// - Parameter publicKey: The 32-byte public key of the remote node
    /// - Returns: The stored password, or nil if not found
    public func retrievePassword(forNodeKey publicKey: Data) async throws -> String? {
        let account = publicKey.base64EncodedString()

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecItemNotFound {
            return nil
        }

        guard status == errSecSuccess,
              let data = result as? Data,
              let password = String(data: data, encoding: .utf8) else {
            throw KeychainError.retrievalFailed(status)
        }

        return password
    }

    /// Delete a stored password for a remote node.
    /// - Parameter publicKey: The 32-byte public key of the remote node
    public func deletePassword(forNodeKey publicKey: Data) async throws {
        let account = publicKey.base64EncodedString()

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]

        let status = SecItemDelete(query as CFDictionary)
        if status != errSecSuccess && status != errSecItemNotFound {
            throw KeychainError.deletionFailed(status)
        }
    }

    /// Check if a password exists for a remote node.
    /// - Parameter publicKey: The 32-byte public key of the remote node
    /// - Returns: True if a password is stored
    public func hasPassword(forNodeKey publicKey: Data) async -> Bool {
        let account = publicKey.base64EncodedString()

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: false
        ]

        return SecItemCopyMatching(query as CFDictionary, nil) == errSecSuccess
    }
}

// MARK: - KeychainError

public enum KeychainError: Error, LocalizedError, Sendable {
    case encodingFailed
    case unexpectedPasswordData
    case storageFailed(OSStatus)
    case retrievalFailed(OSStatus)
    case deletionFailed(OSStatus)

    public var errorDescription: String? {
        switch self {
        case .encodingFailed:
            return "Failed to encode password"
        case .unexpectedPasswordData:
            return "Password data was in unexpected format"
        case .storageFailed(let status):
            return "Failed to store password (error \(status))"
        case .retrievalFailed(let status):
            return "Failed to retrieve password (error \(status))"
        case .deletionFailed(let status):
            return "Failed to delete password (error \(status))"
        }
    }
}

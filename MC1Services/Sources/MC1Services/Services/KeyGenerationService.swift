import CryptoKit
import Foundation

/// Errors that can occur during key generation.
public enum KeyGenerationError: Error, LocalizedError, Sendable {
    /// Generation loop exceeded the maximum number of attempts without finding a matching key.
    case maxAttemptsExceeded

    /// The requested prefix byte is reserved by firmware and cannot be used.
    case reservedPrefix

    /// The system random number generator failed to produce bytes.
    case randomGenerationFailed

    /// The provided key data is not a valid Ed25519 expanded private key.
    case invalidKey

    public var errorDescription: String? {
        switch self {
        case .maxAttemptsExceeded:
            "Could not generate a key with that prefix. Try a different one."
        case .reservedPrefix:
            "That prefix is reserved and cannot be used."
        case .randomGenerationFailed:
            "Secure random number generation failed. Please try again."
        case .invalidKey:
            "The key is not a valid Ed25519 private key."
        }
    }
}

/// Generates Ed25519 keypairs compatible with MeshCore's 64-byte expanded format.
///
/// Key generation uses CryptoKit's `Curve25519.Signing.PrivateKey` and expands the
/// 32-byte seed to MeshCore's 64-byte format using SHA-512 with RFC 8032 bit clamping.
public enum KeyGenerationService: Sendable {

    /// Generates a new Ed25519 identity, optionally matching a vanity hex prefix.
    ///
    /// - Parameter hexPrefix: Optional 1–4 uppercase hex characters the public key should start with.
    ///   Pass `nil` to accept any valid key. Prefixes of 2+ characters starting with `"00"` or `"FF"`
    ///   are rejected as reserved by firmware.
    /// - Returns: A tuple of the 64-byte expanded private key and 32-byte public key.
    /// - Throws: ``KeyGenerationError/reservedPrefix`` if the prefix maps to a reserved byte,
    ///   ``KeyGenerationError/maxAttemptsExceeded`` if no matching key is found,
    ///   or `CancellationError` if the task is cancelled.
    public static func generateIdentity(
        hexPrefix: String?
    ) async throws -> (expandedPrivateKey: Data, publicKey: Data) {
        if let hexPrefix, hexPrefix.count >= 2,
           hexPrefix.hasPrefix("00") || hexPrefix.hasPrefix("FF") {
            throw KeyGenerationError.reservedPrefix
        }

        let attempts = maxAttempts(forPrefixLength: hexPrefix?.count ?? 0)

        for _ in 0..<attempts {
            try Task.checkCancellation()

            var seed = Data(count: 32)
            let status = seed.withUnsafeMutableBytes { buffer in
                SecRandomCopyBytes(kSecRandomDefault, 32, buffer.baseAddress!)
            }
            if status != errSecSuccess {
                throw KeyGenerationError.randomGenerationFailed
            }

            let privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
            let publicKeyData = privateKey.publicKey.rawRepresentation
            let firstByte = publicKeyData[publicKeyData.startIndex]

            // Always reject firmware-reserved prefix bytes
            if firstByte == 0x00 || firstByte == 0xFF {
                continue
            }

            // Check vanity prefix against hex string if requested
            if let hexPrefix {
                let publicHex = publicKeyData.map { String(format: "%02X", $0) }.joined()
                if !publicHex.hasPrefix(hexPrefix) {
                    continue
                }
            }

            let expandedKey = expandSeed(seed)
            return (expandedPrivateKey: expandedKey, publicKey: Data(publicKeyData))
        }

        throw KeyGenerationError.maxAttemptsExceeded
    }

    /// Scales max attempts based on prefix length (~20x the expected attempts needed).
    private static func maxAttempts(forPrefixLength length: Int) -> Int {
        guard length > 0 else { return 10_000 }
        let expected = 1 << (length * 4) // 16^length
        return max(10_000, expected * 20)
    }

    /// Validates that the given data is a properly clamped 64-byte Ed25519 expanded private key.
    ///
    /// Checks RFC 8032 bit clamping on the scalar half (first 32 bytes):
    /// - Byte 0: lowest 3 bits must be clear
    /// - Byte 31: highest bit must be clear, second-highest bit must be set
    ///
    /// - Parameter data: The key data to validate.
    /// - Throws: ``KeyGenerationError/invalidKey`` if the data is not a valid expanded key.
    public static func validateExpandedKey(_ data: Data) throws {
        guard data.count == ProtocolLimits.privateKeySize else {
            throw KeyGenerationError.invalidKey
        }
        // Index relative to startIndex so a non-zero-based slice validates the right bytes.
        let scalarFirst = data[data.startIndex]
        let scalarLast = data[data.index(data.startIndex, offsetBy: 31)]
        // RFC 8032 clamping: lowest 3 bits of byte 0 must be clear
        guard scalarFirst & 0x07 == 0 else {
            throw KeyGenerationError.invalidKey
        }
        // Byte 31: highest bit clear, second-highest set
        guard scalarLast & 0x80 == 0, scalarLast & 0x40 == 0x40 else {
            throw KeyGenerationError.invalidKey
        }
    }

    /// Expands a 32-byte seed to MeshCore's 64-byte format using SHA-512 with RFC 8032 bit clamping.
    private static func expandSeed(_ seed: Data) -> Data {
        var expanded = Data(SHA512.hash(data: seed))
        expanded[0] &= 0xF8
        expanded[31] &= 0x7F
        expanded[31] |= 0x40
        return expanded
    }
}

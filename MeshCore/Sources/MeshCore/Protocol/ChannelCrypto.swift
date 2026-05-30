import Foundation
import CommonCrypto
import CryptoKit

/// Cryptographic operations for MeshCore channel messages.
///
/// Channel messages use AES-128 ECB encryption with HMAC-SHA256 authentication
/// in an Encrypt-then-MAC pattern.
public enum ChannelCrypto {

    /// Size of the truncated HMAC (2 bytes).
    public static let macSize = 2

    /// Size of the AES key (16 bytes for AES-128).
    public static let keySize = 16

    /// Size of the timestamp in decrypted payload (4 bytes).
    public static let timestampSize = 4

    /// Size of the txt_type field in decrypted payload (1 byte).
    public static let txtTypeSize = 1

    /// Total header size before message text: timestamp (4) + txt_type (1) = 5 bytes.
    public static let plaintextHeaderSize = timestampSize + txtTypeSize

    /// Result of attempting to decrypt a channel message.
    public enum DecryptResult: Sendable {
        /// Successfully decrypted the message.
        /// - Parameters:
        ///   - timestamp: 4-byte sender timestamp
        ///   - txtType: Message type indicator (0 = normal text, 1 = command, 2 = signed)
        ///   - text: The decrypted message text
        case success(timestamp: UInt32, txtType: UInt8, text: String)
        /// HMAC verification failed.
        case hmacFailed
        /// Decryption failed (invalid padding or data).
        case decryptFailed
        /// Payload too short to contain required fields.
        case payloadTooShort
    }

    /// Decrypt a channel message payload.
    ///
    /// - Parameters:
    ///   - payload: The channel message payload (after channel index byte).
    ///              Format: [MAC: 2B] [ciphertext: N bytes]
    ///   - secret: The 16-byte channel secret.
    /// - Returns: Decryption result with message text or failure reason.
    public static func decrypt(payload: Data, secret: Data) -> DecryptResult {
        // Minimum: 2 bytes MAC + 16 bytes (1 AES block for timestamp + some text)
        guard payload.count >= macSize + 16 else {
            return .payloadTooShort
        }

        let receivedMAC = payload.prefix(macSize)
        let ciphertext = Data(payload.dropFirst(macSize))

        // Verify HMAC-SHA256 (truncated to 2 bytes)
        let computedMAC = computeHMAC(data: ciphertext, key: secret)
        guard receivedMAC == computedMAC else {
            return .hmacFailed
        }

        // Decrypt using AES-128 ECB
        guard let plaintext = decryptAES128ECB(ciphertext: ciphertext, key: secret) else {
            return .decryptFailed
        }

        // Parse decrypted payload: [timestamp: 4B] [txt_type: 1B] [message: rest]
        guard plaintext.count >= plaintextHeaderSize else {
            return .decryptFailed
        }

        let timestamp = plaintext.prefix(timestampSize).withUnsafeBytes {
            $0.loadUnaligned(as: UInt32.self).littleEndian
        }

        let txtType = plaintext[timestampSize]

        // Extract message text, trimming null padding
        let messageData = plaintext.dropFirst(plaintextHeaderSize)
        let trimmedData = messageData.prefix(while: { $0 != 0 })

        guard let text = String(data: Data(trimmedData), encoding: .utf8) else {
            return .decryptFailed
        }

        return .success(timestamp: timestamp, txtType: txtType, text: text)
    }

    /// Compute truncated HMAC-SHA256.
    private static func computeHMAC(data: Data, key: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let mac = HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey)
        return Data(mac.prefix(macSize))
    }

    /// Decrypt data using AES-128 ECB mode.
    ///
    /// Firmware stores the channel secret as a 32-byte buffer: the first 16 bytes
    /// key AES-128, while all 32 bytes key the HMAC. Accept any secret of at least
    /// the AES key length and use its first 16 bytes, mirroring DirectMessageCrypto.
    private static func decryptAES128ECB(ciphertext: Data, key: Data) -> Data? {
        guard key.count >= kCCKeySizeAES128 else { return nil }
        guard ciphertext.count % kCCBlockSizeAES128 == 0 else { return nil }

        let keyBytes = key.prefix(kCCKeySizeAES128)
        let bufferSize = ciphertext.count
        var decrypted = Data(count: bufferSize)
        var numBytesDecrypted: size_t = 0

        let status = decrypted.withUnsafeMutableBytes { decryptedPtr in
            ciphertext.withUnsafeBytes { ciphertextPtr in
                keyBytes.withUnsafeBytes { keyPtr in
                    CCCrypt(
                        CCOperation(kCCDecrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionECBMode),
                        keyPtr.baseAddress, kCCKeySizeAES128,
                        nil, // No IV for ECB
                        ciphertextPtr.baseAddress, bufferSize,
                        decryptedPtr.baseAddress, bufferSize,
                        &numBytesDecrypted
                    )
                }
            }
        }

        guard status == kCCSuccess else { return nil }
        return Data(decrypted.prefix(numBytesDecrypted))
    }
}

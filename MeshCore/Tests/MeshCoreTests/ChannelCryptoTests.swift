import CommonCrypto
import CryptoKit
import Foundation
import Testing
@testable import MeshCore

@Suite("ChannelCrypto")
struct ChannelCryptoTests {

    // Test channel secret (16 bytes)
    private let testSecret = Data([
        0x8b, 0x33, 0x87, 0xe9, 0xc5, 0xcd, 0xea, 0x6a,
        0xc9, 0xe5, 0xed, 0xba, 0xa1, 0x15, 0xcd, 0x72
    ])

    // MARK: - Helper: Encrypt for testing

    /// Encrypt data using AES-128 ECB (for creating test vectors)
    private func encryptAES128ECB(plaintext: Data, key: Data) -> Data? {
        guard key.count == kCCKeySizeAES128 else { return nil }

        // Pad to block size
        let paddedLength = ((plaintext.count + kCCBlockSizeAES128 - 1) / kCCBlockSizeAES128) * kCCBlockSizeAES128
        var padded = plaintext
        while padded.count < paddedLength {
            padded.append(0)
        }

        var encrypted = Data(count: paddedLength)
        var numBytesEncrypted: size_t = 0

        let status = encrypted.withUnsafeMutableBytes { encryptedPtr in
            padded.withUnsafeBytes { plaintextPtr in
                key.withUnsafeBytes { keyPtr in
                    CCCrypt(
                        CCOperation(kCCEncrypt),
                        CCAlgorithm(kCCAlgorithmAES),
                        CCOptions(kCCOptionECBMode),
                        keyPtr.baseAddress, kCCKeySizeAES128,
                        nil,
                        plaintextPtr.baseAddress, paddedLength,
                        encryptedPtr.baseAddress, paddedLength,
                        &numBytesEncrypted
                    )
                }
            }
        }

        guard status == kCCSuccess else { return nil }
        return Data(encrypted.prefix(numBytesEncrypted))
    }

    /// Compute truncated HMAC-SHA256 (2 bytes)
    private func computeMAC(data: Data, key: Data) -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let mac = HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey)
        return Data(mac.prefix(ChannelCrypto.macSize))
    }

    /// Create an encrypted channel payload for testing
    private func createEncryptedPayload(timestamp: UInt32, txtType: UInt8 = 0, message: String, secret: Data) -> Data? {
        // Build plaintext: [timestamp: 4B] [txt_type: 1B] [message bytes]
        var plaintext = Data()
        var ts = timestamp.littleEndian
        plaintext.append(Data(bytes: &ts, count: 4))
        plaintext.append(txtType)
        plaintext.append(Data(message.utf8))

        // Encrypt
        guard let ciphertext = encryptAES128ECB(plaintext: plaintext, key: secret) else {
            return nil
        }

        // Compute MAC over ciphertext
        let mac = computeMAC(data: ciphertext, key: secret)

        // Return: [MAC: 2B] [ciphertext]
        return mac + ciphertext
    }

    // MARK: - Tests

    @Test("Decrypt success")
    func decryptSuccess() {
        let message = "Alice: Hello mesh!"
        let timestamp: UInt32 = 1703123456
        let txtType: UInt8 = 0  // Normal text

        guard let payload = createEncryptedPayload(
            timestamp: timestamp,
            txtType: txtType,
            message: message,
            secret: testSecret
        ) else {
            Issue.record("Failed to create test payload")
            return
        }

        let result = ChannelCrypto.decrypt(payload: payload, secret: testSecret)

        switch result {
        case .success(let ts, let tt, let text):
            #expect(ts == timestamp)
            #expect(tt == txtType)
            #expect(text == message)
        case .hmacFailed:
            Issue.record("HMAC verification failed")
        case .decryptFailed:
            Issue.record("Decryption failed")
        case .payloadTooShort:
            Issue.record("Payload too short")
        }
    }

    @Test("Decrypt wrong key")
    func decryptWrongKey() {
        let message = "Bob: Secret message"
        let timestamp: UInt32 = 1703123456

        guard let payload = createEncryptedPayload(
            timestamp: timestamp,
            message: message,
            secret: testSecret
        ) else {
            Issue.record("Failed to create test payload")
            return
        }

        // Try to decrypt with wrong key
        let wrongKey = Data([
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF
        ])

        let result = ChannelCrypto.decrypt(payload: payload, secret: wrongKey)

        switch result {
        case .success:
            Issue.record("Should have failed with wrong key")
        case .hmacFailed:
            // Expected - HMAC should fail with wrong key
            break
        case .decryptFailed, .payloadTooShort:
            Issue.record("Wrong failure type - expected hmacFailed")
        }
    }

    @Test("Decrypt corrupted MAC")
    func decryptCorruptedMAC() {
        let message = "Test message"
        let timestamp: UInt32 = 1703123456

        guard var payload = createEncryptedPayload(
            timestamp: timestamp,
            message: message,
            secret: testSecret
        ) else {
            Issue.record("Failed to create test payload")
            return
        }

        // Corrupt the MAC (first 2 bytes)
        payload[0] ^= 0xFF
        payload[1] ^= 0xFF

        let result = ChannelCrypto.decrypt(payload: payload, secret: testSecret)

        switch result {
        case .success:
            Issue.record("Should have failed with corrupted MAC")
        case .hmacFailed:
            // Expected
            break
        case .decryptFailed, .payloadTooShort:
            Issue.record("Wrong failure type - expected hmacFailed")
        }
    }

    @Test("Decrypt payload too short")
    func decryptPayloadTooShort() {
        // Less than minimum: 2 bytes MAC + 16 bytes (1 AES block)
        let shortPayload = Data([0x00, 0x01, 0x02, 0x03])

        let result = ChannelCrypto.decrypt(payload: shortPayload, secret: testSecret)

        switch result {
        case .payloadTooShort:
            // Expected
            break
        default:
            Issue.record("Expected payloadTooShort")
        }
    }

    @Test("Decrypt empty message")
    func decryptEmptyMessage() {
        let message = ""
        let timestamp: UInt32 = 0
        let txtType: UInt8 = 0

        guard let payload = createEncryptedPayload(
            timestamp: timestamp,
            txtType: txtType,
            message: message,
            secret: testSecret
        ) else {
            Issue.record("Failed to create test payload")
            return
        }

        let result = ChannelCrypto.decrypt(payload: payload, secret: testSecret)

        switch result {
        case .success(let ts, let tt, let text):
            #expect(ts == timestamp)
            #expect(tt == txtType)
            #expect(text == message)
        default:
            Issue.record("Expected success for empty message")
        }
    }

    @Test("Decrypt long message")
    func decryptLongMessage() {
        // Message that spans multiple AES blocks (>11 bytes after header)
        let message = "This is a longer message that will definitely span multiple AES blocks for encryption testing"
        let timestamp: UInt32 = 1703123456
        let txtType: UInt8 = 0

        guard let payload = createEncryptedPayload(
            timestamp: timestamp,
            txtType: txtType,
            message: message,
            secret: testSecret
        ) else {
            Issue.record("Failed to create test payload")
            return
        }

        let result = ChannelCrypto.decrypt(payload: payload, secret: testSecret)

        switch result {
        case .success(let ts, let tt, let text):
            #expect(ts == timestamp)
            #expect(tt == txtType)
            #expect(text == message)
        default:
            Issue.record("Expected success for long message")
        }
    }

    @Test("Decrypt unicode message")
    func decryptUnicodeMessage() {
        let message = "Hello! 你好! 🌍"
        let timestamp: UInt32 = 1703123456
        let txtType: UInt8 = 0

        guard let payload = createEncryptedPayload(
            timestamp: timestamp,
            txtType: txtType,
            message: message,
            secret: testSecret
        ) else {
            Issue.record("Failed to create test payload")
            return
        }

        let result = ChannelCrypto.decrypt(payload: payload, secret: testSecret)

        switch result {
        case .success(let ts, let tt, let text):
            #expect(ts == timestamp)
            #expect(tt == txtType)
            #expect(text == message)
        default:
            Issue.record("Expected success for unicode message")
        }
    }

    @Test("Constants")
    func constants() {
        #expect(ChannelCrypto.macSize == 2)
        #expect(ChannelCrypto.keySize == 16)
        #expect(ChannelCrypto.timestampSize == 4)
        #expect(ChannelCrypto.txtTypeSize == 1)
        #expect(ChannelCrypto.plaintextHeaderSize == 5)
    }

    @Test("Decrypt with different txtTypes")
    func decryptWithDifferentTxtTypes() {
        let message = "Test message"
        let timestamp: UInt32 = 1703123456

        // Test all valid txt_type values
        for txtType: UInt8 in [0, 1, 2] {
            guard let payload = createEncryptedPayload(
                timestamp: timestamp,
                txtType: txtType,
                message: message,
                secret: testSecret
            ) else {
                Issue.record("Failed to create test payload for txtType \(txtType)")
                continue
            }

            let result = ChannelCrypto.decrypt(payload: payload, secret: testSecret)

            switch result {
            case .success(let ts, let tt, let text):
                #expect(ts == timestamp)
                #expect(tt == txtType, "txtType mismatch for value \(txtType)")
                #expect(text == message)
            default:
                Issue.record("Expected success for txtType \(txtType)")
            }
        }
    }

    @Test("Decrypt with 32-byte secret uses first 16 bytes for AES")
    func decryptThirtyTwoByteSecret() {
        let message = "Carol: 256-bit key channel"
        let timestamp: UInt32 = 1703123456
        let txtType: UInt8 = 0

        // 32-byte secret whose first 16 bytes match the canonical 16-byte testSecret.
        // Firmware keys AES-128 with the first 16 bytes and HMAC-SHA256 with all 32.
        let secret32 = testSecret + Data([
            0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80,
            0x90, 0xA0, 0xB0, 0xC0, 0xD0, 0xE0, 0xF0, 0x00
        ])

        // Build plaintext: [timestamp: 4B] [txt_type: 1B] [message bytes]
        var plaintext = Data()
        var ts = timestamp.littleEndian
        plaintext.append(Data(bytes: &ts, count: 4))
        plaintext.append(txtType)
        plaintext.append(Data(message.utf8))

        // Encrypt with the 16-byte AES key (the first 16 bytes of the secret).
        guard let ciphertext = encryptAES128ECB(plaintext: plaintext, key: testSecret) else {
            Issue.record("Failed to encrypt test payload")
            return
        }

        // MAC over ciphertext, keyed with the full 32-byte secret.
        let mac = computeMAC(data: ciphertext, key: secret32)
        let payload = mac + ciphertext

        let result = ChannelCrypto.decrypt(payload: payload, secret: secret32)

        switch result {
        case .success(let resultTs, let resultType, let text):
            #expect(resultTs == timestamp)
            #expect(resultType == txtType)
            #expect(text == message)
        default:
            Issue.record("Expected success for 32-byte secret")
        }
    }
}

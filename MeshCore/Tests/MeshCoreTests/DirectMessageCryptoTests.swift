import CommonCrypto
import CryptoKit
import Foundation
@testable import MeshCore
import Testing

@Suite("DirectMessageCrypto")
struct DirectMessageCryptoTests {
  // Test key pair (generated for testing)
  // In real use, keys come from device and contacts
  private let senderPrivateKey = Curve25519.KeyAgreement.PrivateKey()
  private var senderPublicKey: Curve25519.KeyAgreement.PublicKey {
    senderPrivateKey.publicKey
  }

  private let recipientPrivateKey = Curve25519.KeyAgreement.PrivateKey()
  private var recipientPublicKey: Curve25519.KeyAgreement.PublicKey {
    recipientPrivateKey.publicKey
  }

  // MARK: - Helpers

  /// Compute shared secret (same as DirectMessageCrypto should do)
  private func computeSharedSecret(
    myPrivateKey: Curve25519.KeyAgreement.PrivateKey,
    theirPublicKey: Curve25519.KeyAgreement.PublicKey
  ) -> Data {
    // swiftlint:disable:next force_try
    let sharedSecret = try! myPrivateKey.sharedSecretFromKeyAgreement(with: theirPublicKey)
    return sharedSecret.withUnsafeBytes { Data($0) }
  }

  /// Encrypt AES-128-ECB (for creating test vectors)
  private func encryptAES128ECB(plaintext: Data, key: Data) -> Data? {
    guard key.count >= 16 else { return nil }
    let keyBytes = key.prefix(16)

    let paddedLength = ((plaintext.count + 15) / 16) * 16
    var padded = plaintext
    while padded.count < paddedLength {
      padded.append(0)
    }

    var encrypted = Data(count: paddedLength)
    var numBytesEncrypted: size_t = 0

    let status = encrypted.withUnsafeMutableBytes { encryptedPtr in
      padded.withUnsafeBytes { plaintextPtr in
        keyBytes.withUnsafeBytes { keyPtr in
          CCCrypt(
            CCOperation(kCCEncrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode),
            keyPtr.baseAddress, 16,
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
    return Data(mac.prefix(2))
  }

  /// Create encrypted direct message payload for testing
  /// Format: [destHash:1][srcHash:1][MAC:2][ciphertext:N]
  /// Plaintext: [timestamp:4][typeAttempt:1][text:N]
  private func createEncryptedPayload(
    destHash: UInt8,
    srcHash: UInt8,
    timestamp: UInt32,
    typeAttempt: UInt8,
    message: String,
    sharedSecret: Data
  ) -> Data? {
    // Build plaintext
    var plaintext = Data()
    var ts = timestamp.littleEndian
    plaintext.append(Data(bytes: &ts, count: 4))
    plaintext.append(typeAttempt)
    plaintext.append(Data(message.utf8))

    // Encrypt
    guard let ciphertext = encryptAES128ECB(plaintext: plaintext, key: sharedSecret) else {
      return nil
    }

    // Build packet: [destHash][srcHash][MAC][ciphertext]
    // MAC is computed over ciphertext only (per MeshCore spec and ChannelCrypto)
    let mac = computeMAC(data: ciphertext, key: sharedSecret)

    var packet = Data()
    packet.append(destHash)
    packet.append(srcHash)
    packet.append(mac)
    packet.append(ciphertext)

    return packet
  }

  // MARK: - Tests

  @Test
  func `Decrypt success`() throws {
    let sharedSecret = computeSharedSecret(
      myPrivateKey: recipientPrivateKey,
      theirPublicKey: senderPublicKey
    )

    let timestamp: UInt32 = 1_703_123_456
    let typeAttempt: UInt8 = 0
    let message = "Hello from sender!"
    let destHash = try #require(recipientPublicKey.rawRepresentation.first)
    let srcHash = try #require(senderPublicKey.rawRepresentation.first)

    guard let payload = createEncryptedPayload(
      destHash: destHash,
      srcHash: srcHash,
      timestamp: timestamp,
      typeAttempt: typeAttempt,
      message: message,
      sharedSecret: sharedSecret
    ) else {
      Issue.record("Failed to create test payload")
      return
    }

    let result = DirectMessageCrypto.decrypt(
      payload: payload,
      myPrivateKey: Data(recipientPrivateKey.rawRepresentation),
      senderPublicKey: Data(senderPublicKey.rawRepresentation)
    )

    switch result {
    case let .success(ts, ta, text):
      #expect(ts == timestamp)
      #expect(ta == typeAttempt)
      #expect(text == message)
    case .macMismatch:
      Issue.record("MAC verification failed")
    case .decryptionFailed:
      Issue.record("Decryption failed")
    case .invalidPayload:
      Issue.record("Invalid payload")
    case .keyError:
      Issue.record("Key error")
    }
  }

  @Test
  func `Decrypt wrong key`() throws {
    let sharedSecret = computeSharedSecret(
      myPrivateKey: recipientPrivateKey,
      theirPublicKey: senderPublicKey
    )

    let payload = try #require(createEncryptedPayload(
      destHash: 0xAA,
      srcHash: 0xBB,
      timestamp: 1_703_123_456,
      typeAttempt: 0,
      message: "Secret message",
      sharedSecret: sharedSecret
    ))

    // Use wrong key pair
    let wrongPrivateKey = Curve25519.KeyAgreement.PrivateKey()

    let result = DirectMessageCrypto.decrypt(
      payload: payload,
      myPrivateKey: Data(wrongPrivateKey.rawRepresentation),
      senderPublicKey: Data(senderPublicKey.rawRepresentation)
    )

    switch result {
    case .success:
      Issue.record("Should have failed with wrong key")
    case .macMismatch:
      // Expected
      break
    default:
      Issue.record("Expected macMismatch")
    }
  }

  @Test
  func `Decrypt corrupted MAC`() throws {
    let sharedSecret = computeSharedSecret(
      myPrivateKey: recipientPrivateKey,
      theirPublicKey: senderPublicKey
    )

    var payload = try #require(createEncryptedPayload(
      destHash: 0xAA,
      srcHash: 0xBB,
      timestamp: 1_703_123_456,
      typeAttempt: 0,
      message: "Test",
      sharedSecret: sharedSecret
    ))

    // Corrupt MAC (bytes 2 and 3)
    payload[2] ^= 0xFF
    payload[3] ^= 0xFF

    let result = DirectMessageCrypto.decrypt(
      payload: payload,
      myPrivateKey: Data(recipientPrivateKey.rawRepresentation),
      senderPublicKey: Data(senderPublicKey.rawRepresentation)
    )

    switch result {
    case .macMismatch:
      // Expected
      break
    default:
      Issue.record("Expected macMismatch")
    }
  }

  @Test
  func `Decrypt payload too short`() {
    // Less than minimum: 1 + 1 + 2 + 16 = 20 bytes
    let shortPayload = Data([0x00, 0x01, 0x02, 0x03])

    let result = DirectMessageCrypto.decrypt(
      payload: shortPayload,
      myPrivateKey: Data(recipientPrivateKey.rawRepresentation),
      senderPublicKey: Data(senderPublicKey.rawRepresentation)
    )

    switch result {
    case .invalidPayload:
      // Expected
      break
    default:
      Issue.record("Expected invalidPayload")
    }
  }

  @Test
  func `Decrypt empty message`() {
    let sharedSecret = computeSharedSecret(
      myPrivateKey: recipientPrivateKey,
      theirPublicKey: senderPublicKey
    )

    guard let payload = createEncryptedPayload(
      destHash: 0xAA,
      srcHash: 0xBB,
      timestamp: 0,
      typeAttempt: 0,
      message: "",
      sharedSecret: sharedSecret
    ) else {
      Issue.record("Failed to create test payload")
      return
    }

    let result = DirectMessageCrypto.decrypt(
      payload: payload,
      myPrivateKey: Data(recipientPrivateKey.rawRepresentation),
      senderPublicKey: Data(senderPublicKey.rawRepresentation)
    )

    switch result {
    case let .success(ts, ta, text):
      #expect(ts == 0)
      #expect(ta == 0)
      #expect(text == "")
    default:
      Issue.record("Expected success")
    }
  }

  @Test
  func `Decrypt unicode message`() {
    let sharedSecret = computeSharedSecret(
      myPrivateKey: recipientPrivateKey,
      theirPublicKey: senderPublicKey
    )

    let message = "Hello! \u{4F60}\u{597D}! \u{1F30D}"

    guard let payload = createEncryptedPayload(
      destHash: 0xAA,
      srcHash: 0xBB,
      timestamp: 1_703_123_456,
      typeAttempt: 0,
      message: message,
      sharedSecret: sharedSecret
    ) else {
      Issue.record("Failed to create test payload")
      return
    }

    let result = DirectMessageCrypto.decrypt(
      payload: payload,
      myPrivateKey: Data(recipientPrivateKey.rawRepresentation),
      senderPublicKey: Data(senderPublicKey.rawRepresentation)
    )

    switch result {
    case let .success(_, _, text):
      #expect(text == message)
    default:
      Issue.record("Expected success")
    }
  }

  @Test
  func `Extract timestamp`() {
    let sharedSecret = computeSharedSecret(
      myPrivateKey: recipientPrivateKey,
      theirPublicKey: senderPublicKey
    )

    let expectedTimestamp: UInt32 = 1_703_123_456

    guard let payload = createEncryptedPayload(
      destHash: 0xAA,
      srcHash: 0xBB,
      timestamp: expectedTimestamp,
      typeAttempt: 0,
      message: "Test",
      sharedSecret: sharedSecret
    ) else {
      Issue.record("Failed to create test payload")
      return
    }

    let timestamp = DirectMessageCrypto.extractTimestamp(
      payload: payload,
      myPrivateKey: Data(recipientPrivateKey.rawRepresentation),
      senderPublicKey: Data(senderPublicKey.rawRepresentation)
    )

    #expect(timestamp == expectedTimestamp)
  }

  @Test
  func constants() {
    #expect(DirectMessageCrypto.macSize == 2)
    #expect(DirectMessageCrypto.headerSize == 2)
    #expect(DirectMessageCrypto.timestampSize == 4)
    #expect(DirectMessageCrypto.typeAttemptSize == 1)
    #expect(DirectMessageCrypto.minCiphertextSize == 16)
    #expect(DirectMessageCrypto.minPacketSize == 20)
  }

  @Test
  func `Invalid key length`() {
    let payload = Data(repeating: 0, count: 24)

    let result = DirectMessageCrypto.decrypt(
      payload: payload,
      myPrivateKey: Data([0x01, 0x02]), // Too short
      senderPublicKey: Data(repeating: 0, count: 32)
    )

    switch result {
    case .keyError:
      // Expected
      break
    default:
      Issue.record("Expected keyError")
    }
  }
}

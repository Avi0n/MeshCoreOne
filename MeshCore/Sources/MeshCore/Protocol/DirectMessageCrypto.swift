import CommonCrypto
import CryptoKit
import Foundation

/// Cryptographic operations for MeshCore direct (peer-to-peer) messages.
///
/// Direct messages use ECDH Curve25519 key exchange with AES-128 ECB encryption
/// and HMAC-SHA256 authentication in an Encrypt-then-MAC pattern.
///
/// Packet format: [destHash:1][srcHash:1][MAC:2][ciphertext:N]
/// Decrypted payload: [timestamp:4][typeAttempt:1][text:N]
public enum DirectMessageCrypto {
  /// Size of the truncated HMAC (2 bytes).
  public static let macSize = 2

  /// Size of the header (destHash + srcHash = 2 bytes).
  public static let headerSize = 2

  /// Size of the timestamp in decrypted payload (4 bytes).
  public static let timestampSize = 4

  /// Size of the typeAttempt field (1 byte).
  public static let typeAttemptSize = 1

  /// Minimum ciphertext size (one AES block).
  public static let minCiphertextSize = 16

  /// Minimum packet size: header + mac + one AES block.
  public static let minPacketSize = headerSize + macSize + minCiphertextSize

  /// Result of attempting to decrypt a direct message.
  public enum DecryptResult: Sendable, Equatable {
    /// Successfully decrypted the message.
    case success(timestamp: UInt32, typeAttempt: UInt8, text: String?)

    /// MAC verification failed (wrong key or corrupted packet).
    case macMismatch

    /// Decryption failed (invalid ciphertext).
    case decryptionFailed

    /// Payload too short or malformed.
    case invalidPayload

    /// Key derivation failed (invalid key data).
    case keyError
  }

  /// Decrypt a direct message payload.
  ///
  /// - Parameters:
  ///   - payload: Raw packet [destHash:1][srcHash:1][MAC:2][ciphertext:N]
  ///   - myPrivateKey: Recipient's 32-byte Curve25519 private key
  ///   - senderPublicKey: Sender's 32-byte Curve25519 public key
  /// - Returns: DecryptResult with timestamp and text on success
  public static func decrypt(
    payload: Data,
    myPrivateKey: Data,
    senderPublicKey: Data
  ) -> DecryptResult {
    // Validate payload size
    guard payload.count >= minPacketSize else {
      return .invalidPayload
    }

    // Compute shared secret
    guard let sharedSecret = computeSharedSecret(
      myPrivateKey: myPrivateKey,
      theirPublicKey: senderPublicKey
    ) else {
      return .keyError
    }

    // Extract packet components
    let receivedMAC = payload[headerSize..<(headerSize + macSize)]
    let ciphertext = Data(payload.dropFirst(headerSize + macSize))

    // Verify MAC over ciphertext only (per MeshCore spec and ChannelCrypto)
    let computedMAC = computeHMAC(data: ciphertext, key: sharedSecret)
    guard receivedMAC == computedMAC else {
      return .macMismatch
    }

    // Decrypt using AES-128 ECB with first 16 bytes of shared secret
    guard let plaintext = decryptAES128ECB(ciphertext: ciphertext, key: sharedSecret) else {
      return .decryptionFailed
    }

    // Parse decrypted payload: [timestamp:4][typeAttempt:1][text:rest]
    guard plaintext.count >= timestampSize + typeAttemptSize else {
      return .decryptionFailed
    }

    let timestamp = plaintext.prefix(timestampSize).withUnsafeBytes {
      $0.loadUnaligned(as: UInt32.self).littleEndian
    }

    let typeAttempt = plaintext[timestampSize]

    // Extract message text, trimming null padding
    let messageData = plaintext.dropFirst(timestampSize + typeAttemptSize)
    let trimmedData = messageData.prefix(while: { $0 != 0 })

    let text: String? = if trimmedData.isEmpty {
      ""
    } else {
      String(data: Data(trimmedData), encoding: .utf8)
    }

    return .success(timestamp: timestamp, typeAttempt: typeAttempt, text: text)
  }

  /// Extract only the senderTimestamp from a direct message.
  /// Convenience wrapper around decrypt() for RxLogService use case.
  ///
  /// - Returns: Timestamp if decryption succeeds, nil otherwise
  public static func extractTimestamp(
    payload: Data,
    myPrivateKey: Data,
    senderPublicKey: Data
  ) -> UInt32? {
    if case let .success(timestamp, _, _) = decrypt(
      payload: payload,
      myPrivateKey: myPrivateKey,
      senderPublicKey: senderPublicKey
    ) {
      return timestamp
    }
    return nil
  }

  // MARK: - Private Helpers

  /// Compute ECDH shared secret using Curve25519.
  private static func computeSharedSecret(
    myPrivateKey: Data,
    theirPublicKey: Data
  ) -> Data? {
    guard myPrivateKey.count == PacketBuilder.publicKeySize, theirPublicKey.count == PacketBuilder.publicKeySize else {
      return nil
    }

    do {
      let privateKey = try Curve25519.KeyAgreement.PrivateKey(
        rawRepresentation: myPrivateKey
      )
      let publicKey = try Curve25519.KeyAgreement.PublicKey(
        rawRepresentation: theirPublicKey
      )
      let sharedSecret = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
      return sharedSecret.withUnsafeBytes { Data($0) }
    } catch {
      return nil
    }
  }

  /// Compute truncated HMAC-SHA256 (2 bytes).
  private static func computeHMAC(data: Data, key: Data) -> Data {
    let symmetricKey = SymmetricKey(data: key)
    let mac = HMAC<SHA256>.authenticationCode(for: data, using: symmetricKey)
    return Data(mac.prefix(macSize))
  }

  /// Decrypt data using AES-128 ECB mode.
  private static func decryptAES128ECB(ciphertext: Data, key: Data) -> Data? {
    guard key.count >= kCCKeySizeAES128 else { return nil }
    guard ciphertext.count % kCCBlockSizeAES128 == 0 else { return nil }

    let keyBytes = key.prefix(kCCKeySizeAES128)
    var decrypted = Data(count: ciphertext.count)
    var numBytesDecrypted: size_t = 0

    let status = decrypted.withUnsafeMutableBytes { decryptedPtr in
      ciphertext.withUnsafeBytes { ciphertextPtr in
        keyBytes.withUnsafeBytes { keyPtr in
          CCCrypt(
            CCOperation(kCCDecrypt),
            CCAlgorithm(kCCAlgorithmAES),
            CCOptions(kCCOptionECBMode),
            keyPtr.baseAddress, kCCKeySizeAES128,
            nil,
            ciphertextPtr.baseAddress, ciphertext.count,
            decryptedPtr.baseAddress, ciphertext.count,
            &numBytesDecrypted
          )
        }
      }
    }

    guard status == kCCSuccess else { return nil }
    return Data(decrypted.prefix(numBytesDecrypted))
  }
}

#if canImport(CommonCrypto)
  import CommonCrypto
#endif
#if canImport(CryptoKit)
  import CryptoKit
#else
  import Crypto
#endif
import Foundation

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

  /// Little-endian data type at the start of a group-datagram plaintext.
  public static let groupDatagramTypeSize = 2

  /// Length byte that follows the group-datagram data type.
  public static let groupDatagramLengthSize = 1

  /// Bytes before the group-datagram blob: data type (2) + length (1).
  public static let groupDatagramHeaderSize = groupDatagramTypeSize + groupDatagramLengthSize

  /// 1-byte wire channel hash matching firmware `BaseChatMesh::setChannel`:
  /// hashes the first 16 bytes when a 32-byte secret is zero-padded, else all bytes.
  public static func channelHash(for secret: Data) -> UInt8 {
    let keyData: Data = if secret.count == 32, secret.dropFirst(keySize).allSatisfy({ $0 == 0 }) {
      Data(secret.prefix(keySize))
    } else {
      secret
    }
    let digest = SHA256.hash(data: keyData)
    return digest.withUnsafeBytes { $0[0] }
  }

  /// MAC-checked AES plaintext, still including block padding.
  public enum PlaintextResult: Sendable {
    case success(Data)
    case hmacFailed
    case decryptFailed
    case payloadTooShort
  }

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

  /// MAC-check and AES-128-ECB decrypt. The plaintext still includes block padding.
  /// AES uses the first 16 secret bytes. HMAC uses the whole secret.
  public static func decryptPlaintext(payload: Data, secret: Data) -> PlaintextResult {
    guard payload.count >= macSize + 16 else {
      return .payloadTooShort
    }

    let receivedMAC = payload.prefix(macSize)
    let ciphertext = Data(payload.dropFirst(macSize))
    let computedMAC = computeHMAC(data: ciphertext, key: secret)
    guard receivedMAC == computedMAC else {
      return .hmacFailed
    }

    guard let plaintext = decryptAES128ECB(ciphertext: ciphertext, key: secret) else {
      return .decryptFailed
    }
    return .success(plaintext)
  }

  /// Decrypt a group-text payload into a timestamp, text type, and UTF-8 body.
  ///
  /// - Parameters:
  ///   - payload: The channel message payload (after channel index byte).
  ///              Format: [MAC: 2B] [ciphertext: N bytes]
  ///   - secret: The 16-byte channel secret.
  /// - Returns: Decryption result with message text or failure reason.
  public static func decrypt(payload: Data, secret: Data) -> DecryptResult {
    switch decryptPlaintext(payload: payload, secret: secret) {
    case .hmacFailed:
      .hmacFailed
    case .decryptFailed:
      .decryptFailed
    case .payloadTooShort:
      .payloadTooShort
    case let .success(plaintext):
      parseGroupText(plaintext)
    }
  }

  /// Little-endian data type, one length byte, then that blob.
  /// A short header or a length past the plaintext returns nil. Padding after the blob is not the data field.
  public static func readGroupDatagram(_ plaintext: Data) -> (dataType: UInt16, data: Data)? {
    guard plaintext.count >= groupDatagramHeaderSize else { return nil }
    let dataType = plaintext.readUInt16LE(at: 0)
    let declared = Int(plaintext[plaintext.startIndex + groupDatagramTypeSize])
    let available = plaintext.count - groupDatagramHeaderSize
    guard declared <= available else { return nil }
    let body = plaintext.dropFirst(groupDatagramHeaderSize).prefix(declared)
    return (dataType, Data(body))
  }

  private static func parseGroupText(_ plaintext: Data) -> DecryptResult {
    guard plaintext.count >= plaintextHeaderSize else {
      return .decryptFailed
    }

    let timestamp = plaintext.prefix(timestampSize).withUnsafeBytes {
      $0.loadUnaligned(as: UInt32.self).littleEndian
    }
    let txtType = plaintext[plaintext.startIndex + timestampSize]
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
  ///
  /// Internal rather than private so cross-backend fixtures can pin its output.
  /// The non-Apple backend must reproduce that output byte for byte.
  static func decryptAES128ECB(ciphertext: Data, key: Data) -> Data? {
    #if canImport(CommonCrypto)
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
    #else
      return AES128ECBFallback.decrypt(ciphertext: ciphertext, key: key)
    #endif
  }
}

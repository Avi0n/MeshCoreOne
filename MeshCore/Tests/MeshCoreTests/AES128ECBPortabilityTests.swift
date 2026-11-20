import Foundation
@testable import MeshCore
import Testing

/// Golden vectors for the AES-128-ECB decrypt path.
///
/// Each build runs the backend it ships. Apple runs CommonCrypto. Other platforms
/// run the CryptoExtras fallback. Every expectation is a hardcoded value, so both
/// backends are held to the same bytes.
@Suite("AES128ECB portability")
struct AES128ECBPortabilityTests {
  /// Decode a lowercase hex string into raw bytes.
  private func bytes(_ hex: String) -> Data {
    var data = Data(capacity: hex.count / 2)
    var index = hex.startIndex
    while index < hex.endIndex {
      let next = hex.index(index, offsetBy: 2)
      data.append(UInt8(hex[index..<next], radix: 16)!)
      index = next
    }
    return data
  }

  private func hex(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
  }

  // MARK: - Golden decrypt vectors (full public API)

  @Test
  func `Channel golden vector decrypts to Alice's message`() {
    let secret = bytes("8b3387e9c5cdea6ac9e5edbaa115cd72")
    let payload = bytes("a0e6215565cf0555d9e4a5e4824dc70018a6021e8232bb0509500bdf22a90e97978f")

    let result = ChannelCrypto.decrypt(payload: payload, secret: secret)

    guard case let .success(timestamp, txtType, text) = result else {
      Issue.record("Expected success, got \(result)")
      return
    }
    #expect(timestamp == 1_703_123_456)
    #expect(txtType == 0)
    #expect(text == "Alice: Hello mesh!")
  }

  @Test
  func `Direct-message golden vector decrypts to Bob's message`() {
    // Deterministic vector generated on macOS against the CommonCrypto backend:
    // recipient private key + sender public key + packet, ECDH Curve25519 + AES-128-ECB.
    let recipientPrivateKey = bytes("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
    let senderPublicKey = bytes("493e82fc74464a59268817623d2053c5eb8e2cc4a988b4fee179ec6b010d531d")
    let payload = bytes("7949de3e62023be8bf381289cbcac08ab1806843471af02034b07334c365c90be841bb86")

    let result = DirectMessageCrypto.decrypt(
      payload: payload,
      myPrivateKey: recipientPrivateKey,
      senderPublicKey: senderPublicKey
    )

    #expect(result == .success(timestamp: 1_703_123_456, typeAttempt: 0, text: "Bob: DM golden vector"))
  }

  // MARK: - Edge-case semantics pinned against the CommonCrypto backend

  /// Any 16-byte block and the two keys used across the edge cases.
  private let block = Data(repeating: 0xAB, count: 16)
  private let key16 = Data((0..<16).map { UInt8($0) })
  private let key15 = Data((0..<15).map { UInt8($0) })
  private let key32 = Data((0..<32).map { UInt8($0) })
  /// Decryption of `block` under the first 16 bytes of the key sequence.
  private let blockPlaintext = "ee237802b6ecc7c37391f79115ba0d6a"

  @Test
  func `Empty ciphertext yields empty data, not nil`() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: Data(), key: key16)
    #expect(result == Data())
  }

  @Test
  func `Ciphertext length not a multiple of the block size yields nil`() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: Data(repeating: 0x01, count: 17), key: key16)
    #expect(result == nil)
  }

  @Test
  func `Key shorter than the AES key length yields nil`() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: block, key: key15)
    #expect(result == nil)
  }

  @Test
  func `Key longer than the AES key length uses only the first 16 bytes`() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: block, key: key32)
    #expect(result.map(hex) == blockPlaintext)
    #expect(ChannelCrypto.decryptAES128ECB(ciphertext: block, key: key32.prefix(16)).map(hex) == blockPlaintext)
  }

  @Test
  func `Direct-message backend shares the same edge-case semantics`() {
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: Data(), key: key16) == Data())
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: Data(repeating: 0x01, count: 17), key: key16) == nil)
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: block, key: key15) == nil)
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: block, key: key32).map(hex) == blockPlaintext)
  }

  /// Raw ECB plaintext for the 32-byte channel ciphertext, including the timestamp,
  /// text type, message, and zero padding. Both backends must produce these bytes.
  private let twoBlockPlaintext = "009a836500416c6963653a2048656c6c6f206d65736821000000000000000000"

  @Test
  func `Two-block channel ciphertext matches the pinned plaintext`() {
    let secret = bytes("8b3387e9c5cdea6ac9e5edbaa115cd72")
    let payload = bytes("a0e6215565cf0555d9e4a5e4824dc70018a6021e8232bb0509500bdf22a90e97978f")
    let ciphertext = Data(payload.dropFirst(ChannelCrypto.macSize))

    #expect(ciphertext.count == 32)
    let plaintext = ChannelCrypto.decryptAES128ECB(ciphertext: ciphertext, key: secret)
    #expect(plaintext.map(hex) == twoBlockPlaintext)
  }
}

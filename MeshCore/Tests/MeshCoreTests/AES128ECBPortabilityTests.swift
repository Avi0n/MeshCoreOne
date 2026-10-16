import Foundation
@testable import MeshCore
import Testing

/// Cross-backend golden vectors for the AES-128-ECB decrypt paths.
///
/// These pin the exact bytes the Apple CommonCrypto backend produces so the
/// non-Apple backend can be held to the same output. Every expectation here is
/// a hardcoded value, not a re-derivation, so the two backends are compared
/// against one fixed source of truth rather than against each other.
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

  @Test("Channel golden vector decrypts to Alice's message")
  func channelGoldenVector() {
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

  @Test("Direct-message golden vector decrypts to Bob's message")
  func directMessageGoldenVector() {
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

  @Test("Empty ciphertext yields empty data, not nil")
  func channelEmptyCiphertext() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: Data(), key: key16)
    #expect(result == Data())
  }

  @Test("Ciphertext length not a multiple of the block size yields nil")
  func channelNonBlockAlignedCiphertext() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: Data(repeating: 0x01, count: 17), key: key16)
    #expect(result == nil)
  }

  @Test("Key shorter than the AES key length yields nil")
  func channelShortKey() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: block, key: key15)
    #expect(result == nil)
  }

  @Test("Key longer than the AES key length uses only the first 16 bytes")
  func channelLongKeyUsesFirst16() {
    let result = ChannelCrypto.decryptAES128ECB(ciphertext: block, key: key32)
    #expect(result.map(hex) == blockPlaintext)
    #expect(ChannelCrypto.decryptAES128ECB(ciphertext: block, key: key32.prefix(16)).map(hex) == blockPlaintext)
  }

  @Test("Direct-message backend shares the same edge-case semantics")
  func directMessageEdgeCases() {
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: Data(), key: key16) == Data())
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: Data(repeating: 0x01, count: 17), key: key16) == nil)
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: block, key: key15) == nil)
    #expect(DirectMessageCrypto.decryptAES128ECB(ciphertext: block, key: key32).map(hex) == blockPlaintext)
  }

  /// The non-Apple backend is compiled on every platform, so on Apple it can be
  /// held to the exact same pinned values the CommonCrypto path produces.
  @Test("Non-Apple fallback backend reproduces every pinned value")
  func fallbackMatchesPinnedValues() {
    #expect(AES128ECBFallback.decrypt(ciphertext: Data(), key: key16) == Data())
    #expect(AES128ECBFallback.decrypt(ciphertext: Data(repeating: 0x01, count: 17), key: key16) == nil)
    #expect(AES128ECBFallback.decrypt(ciphertext: block, key: key15) == nil)
    #expect(AES128ECBFallback.decrypt(ciphertext: block, key: key32).map(hex) == blockPlaintext)
    #expect(AES128ECBFallback.decrypt(ciphertext: block, key: key32.prefix(16)).map(hex) == blockPlaintext)
  }
}

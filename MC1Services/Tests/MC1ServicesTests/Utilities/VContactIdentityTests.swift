import CryptoKit
import Foundation
@testable import MC1Services
import Testing

@Suite("VContactIdentity Tests")
struct VContactIdentityTests {
  /// Self key: 0x01 followed by 31 zero bytes.
  private static let selfKeyA = Data([0x01] + Array(repeating: UInt8(0), count: 31))

  /// Self key: 0xAB repeated 32 times.
  private static let selfKeyB = Data(repeating: 0xAB, count: 32)

  /// SHA256("zc-vcontact" || selfKeyA)
  private static let expectedVA = Data(hexString: "a987552bd0518c37b4366e1cfb6df735a2e6d4c913385c32f5ea45980b459a73")!

  /// SHA256("zc-vcontact" || selfKeyB)
  private static let expectedVB = Data(hexString: "b02d900beb616f0ed4ff090cdce125630b8149f423bd7498442d66c4ca7bc083")!

  @Test
  func `Salt is eleven bytes without null terminator`() {
    #expect(VContactIdentity.salt.count == 11)
    #expect(VContactIdentity.salt == Data("zc-vcontact".utf8))
  }

  @Test
  func `Derives golden V-contact public key for known self key`() throws {
    let derived = try #require(VContactIdentity.publicKey(forSelfPublicKey: Self.selfKeyA))
    #expect(derived == Self.expectedVA)
    #expect(derived.count == ProtocolLimits.publicKeySize)
  }

  @Test
  func `Different self keys produce different V-contact keys`() {
    let a = VContactIdentity.publicKey(forSelfPublicKey: Self.selfKeyA)
    let b = VContactIdentity.publicKey(forSelfPublicKey: Self.selfKeyB)
    #expect(a == Self.expectedVA)
    #expect(b == Self.expectedVB)
    #expect(a != b)
  }

  @Test
  func `Derivation matches manual CryptoKit concatenation`() {
    var input = Data()
    input.append(VContactIdentity.salt)
    input.append(Self.selfKeyA)
    let manual = Data(SHA256.hash(data: input))
    #expect(VContactIdentity.publicKey(forSelfPublicKey: Self.selfKeyA) == manual)
  }

  @Test
  func `isVContact matches only derived key`() {
    #expect(VContactIdentity.isVContact(publicKey: Self.expectedVA, selfPublicKey: Self.selfKeyA))
    #expect(!VContactIdentity.isVContact(publicKey: Self.expectedVB, selfPublicKey: Self.selfKeyA))
    #expect(!VContactIdentity.isVContact(publicKey: Self.selfKeyA, selfPublicKey: Self.selfKeyA))
  }

  @Test
  func `Invalid key lengths fail open as non-V`() {
    #expect(VContactIdentity.publicKey(forSelfPublicKey: Data()) == nil)
    #expect(VContactIdentity.publicKey(forSelfPublicKey: Data(repeating: 1, count: 16)) == nil)
    #expect(!VContactIdentity.isVContact(publicKey: Self.expectedVA, selfPublicKey: Data()))
    #expect(!VContactIdentity.isVContact(publicKey: Data(), selfPublicKey: Self.selfKeyA))
  }
}

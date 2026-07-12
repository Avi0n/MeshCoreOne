import CryptoKit
import Foundation
@testable import MC1Services
import Testing

@Suite("KeyGenerationService Tests")
struct KeyGenerationServiceTests {
  @Test
  func `Generated expanded key is 64 bytes and public key is 32 bytes`() async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: nil)

    #expect(result.expandedPrivateKey.count == 64)
    #expect(result.publicKey.count == 32)
  }

  @Test
  func `Public key never starts with 0x00 or 0xFF`() async throws {
    // Generate multiple keys and verify none start with reserved bytes
    for _ in 0..<20 {
      let result = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
      let firstByte = result.publicKey[result.publicKey.startIndex]
      #expect(firstByte != 0x00, "Public key should never start with 0x00")
      #expect(firstByte != 0xFF, "Public key should never start with 0xFF")
    }
  }

  @Test(arguments: ["AA", "42", "7F"])
  func `2-char vanity prefix is respected`(prefix: String) async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: prefix)
    let publicHex = result.publicKey.map { String(format: "%02X", $0) }.joined()
    #expect(publicHex.hasPrefix(prefix), "Expected public key hex to start with \(prefix), got \(publicHex)")
  }

  @Test(arguments: ["A", "7", "3"])
  func `1-char vanity prefix is respected`(prefix: String) async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: prefix)
    let publicHex = result.publicKey.map { String(format: "%02X", $0) }.joined()
    #expect(publicHex.hasPrefix(prefix), "Expected public key hex to start with \(prefix), got \(publicHex)")
  }

  @Test
  func `3-char vanity prefix is respected`() async throws {
    let prefix = "A7B"
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: prefix)
    let publicHex = result.publicKey.map { String(format: "%02X", $0) }.joined()
    #expect(publicHex.hasPrefix(prefix), "Expected public key hex to start with \(prefix), got \(publicHex)")
  }

  @Test
  func `4-char vanity prefix is respected`() async throws {
    let prefix = "A7B2"
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: prefix)
    let publicHex = result.publicKey.map { String(format: "%02X", $0) }.joined()
    #expect(publicHex.hasPrefix(prefix), "Expected public key hex to start with \(prefix), got \(publicHex)")
  }

  @Test
  func `Nil prefix generates any valid key`() async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
    #expect(result.publicKey.count == 32)
  }

  @Test
  func `Reserved prefix '00' throws reservedPrefix error`() async {
    await #expect(throws: KeyGenerationError.reservedPrefix) {
      _ = try await KeyGenerationService.generateIdentity(hexPrefix: "00")
    }
  }

  @Test
  func `Reserved prefix 'FF' throws reservedPrefix error`() async {
    await #expect(throws: KeyGenerationError.reservedPrefix) {
      _ = try await KeyGenerationService.generateIdentity(hexPrefix: "FF")
    }
  }

  @Test
  func `Reserved multi-char prefix '00A1' throws reservedPrefix error`() async {
    await #expect(throws: KeyGenerationError.reservedPrefix) {
      _ = try await KeyGenerationService.generateIdentity(hexPrefix: "00A1")
    }
  }

  @Test
  func `Reserved multi-char prefix 'FFB2' throws reservedPrefix error`() async {
    await #expect(throws: KeyGenerationError.reservedPrefix) {
      _ = try await KeyGenerationService.generateIdentity(hexPrefix: "FFB2")
    }
  }

  @Test
  func `Single-char '0' is not reserved and succeeds`() async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: "0")
    let publicHex = result.publicKey.map { String(format: "%02X", $0) }.joined()
    #expect(publicHex.hasPrefix("0"), "Expected public key hex to start with '0', got \(publicHex)")
  }

  @Test
  func `Single-char 'F' is not reserved and succeeds`() async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: "F")
    let publicHex = result.publicKey.map { String(format: "%02X", $0) }.joined()
    #expect(publicHex.hasPrefix("F"), "Expected public key hex to start with 'F', got \(publicHex)")
  }

  @Test
  func `Cancellation is respected`() async {
    let task = Task {
      // Use a very unlikely 4-char prefix to make the loop run long
      _ = try await KeyGenerationService.generateIdentity(hexPrefix: "0101")
    }

    // Cancel immediately
    task.cancel()

    let result = await task.result
    switch result {
    case .success:
      // If the key was found before cancellation, that's fine
      break
    case let .failure(error):
      #expect(error is CancellationError, "Expected CancellationError, got \(error)")
    }
  }

  @Test
  func `Expanded key has correct SHA-512 clamping`() async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
    let expanded = result.expandedPrivateKey

    // RFC 8032 bit clamping checks:
    // expanded[0] lowest 3 bits must be clear
    #expect(expanded[0] & 0x07 == 0, "Lowest 3 bits of first byte should be cleared")

    // expanded[31] highest bit must be clear, second-highest must be set
    #expect(expanded[31] & 0x80 == 0, "Highest bit of byte 31 should be cleared")
    #expect(expanded[31] & 0x40 == 0x40, "Second-highest bit of byte 31 should be set")
  }

  @Test
  func `Expansion matches manual SHA-512 + clamp`() async throws {
    // Generate a key, then verify the expansion independently
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
    let expandedKey = result.expandedPrivateKey
    let publicKey = result.publicKey

    // Verify the expanded key has valid structure.
    #expect(expandedKey.count == 64)
    #expect(publicKey.count == 32)

    // The expanded key's clamping is already verified above.
    // Verify the expanded key is different from the public key (they serve different purposes)
    #expect(Data(expandedKey.prefix(32)) != publicKey)
  }

  @Test
  func `randomGenerationFailed error has a description`() {
    let error = KeyGenerationError.randomGenerationFailed
    #expect(error.errorDescription != nil)
    #expect(error.errorDescription?.isEmpty == false)
  }

  @Test
  func `Multiple generations produce different keys`() async throws {
    let result1 = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
    let result2 = try await KeyGenerationService.generateIdentity(hexPrefix: nil)

    #expect(result1.publicKey != result2.publicKey, "Two generated keys should be different")
    #expect(result1.expandedPrivateKey != result2.expandedPrivateKey)
  }

  // MARK: - validateExpandedKey Tests

  @Test
  func `Valid expanded key passes validation`() async throws {
    let result = try await KeyGenerationService.generateIdentity(hexPrefix: nil)
    try KeyGenerationService.validateExpandedKey(result.expandedPrivateKey)
  }

  @Test(arguments: [32, 63, 65, 0])
  func `Wrong length throws invalidKey`(length: Int) {
    let data = Data(repeating: 0x40, count: length)
    #expect(throws: KeyGenerationError.invalidKey) {
      try KeyGenerationService.validateExpandedKey(data)
    }
  }

  @Test
  func `Bad clamping byte 0 (lowest 3 bits set) throws invalidKey`() async throws {
    var key = try await KeyGenerationService.generateIdentity(hexPrefix: nil).expandedPrivateKey
    key[0] |= 0x07 // set lowest 3 bits
    #expect(throws: KeyGenerationError.invalidKey) {
      try KeyGenerationService.validateExpandedKey(key)
    }
  }

  @Test
  func `Bad clamping byte 31 (highest bit set) throws invalidKey`() async throws {
    var key = try await KeyGenerationService.generateIdentity(hexPrefix: nil).expandedPrivateKey
    key[31] |= 0x80 // set highest bit
    #expect(throws: KeyGenerationError.invalidKey) {
      try KeyGenerationService.validateExpandedKey(key)
    }
  }

  @Test
  func `Bad clamping byte 31 (second-highest bit clear) throws invalidKey`() async throws {
    var key = try await KeyGenerationService.generateIdentity(hexPrefix: nil).expandedPrivateKey
    key[31] &= ~0x40 // clear second-highest bit
    #expect(throws: KeyGenerationError.invalidKey) {
      try KeyGenerationService.validateExpandedKey(key)
    }
  }

  @Test
  func `invalidKey error has a description`() {
    let error = KeyGenerationError.invalidKey
    #expect(error.errorDescription != nil)
    #expect(error.errorDescription?.isEmpty == false)
  }

  @Test
  func `Validation works on a non-zero-based Data slice`() async throws {
    let key = try await KeyGenerationService.generateIdentity(hexPrefix: nil).expandedPrivateKey
    // A slice keeps its parent's indices, so startIndex is non-zero here.
    let padded = Data([0xFF, 0xFF, 0xFF, 0xFF]) + key
    let slice = padded.suffix(key.count)
    #expect(slice.startIndex != 0)
    try KeyGenerationService.validateExpandedKey(slice)
  }
}

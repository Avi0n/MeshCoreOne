import Foundation
@testable import MeshCore
import Testing

@Suite("decodePathLen")
struct DecodePathLenTests {
  // MARK: - Mode 0 (1-byte hashes)

  @Test
  func `mode 0 with 5 hops`() {
    // 0b00_000101 = mode 0, 5 hops
    let encoded: UInt8 = 0b0000_0101
    let result = decodePathLen(encoded)

    #expect(result != nil, "Mode 0 should be valid")
    #expect(result?.hashSize == 1, "Mode 0 → 1-byte hashes")
    #expect(result?.hopCount == 5, "Lower 6 bits = 5")
    #expect(result?.byteLength == 5, "1 * 5 = 5 bytes on wire")
  }

  @Test
  func `mode 0 with 0 hops`() {
    // 0b00_000000 = mode 0, 0 hops
    let encoded: UInt8 = 0x00
    let result = decodePathLen(encoded)

    #expect(result != nil, "Mode 0 with 0 hops should be valid")
    #expect(result?.hashSize == 1)
    #expect(result?.hopCount == 0)
    #expect(result?.byteLength == 0, "1 * 0 = 0 bytes on wire")
  }

  @Test
  func `mode 0 with max hops (63)`() {
    // 0b00_111111 = mode 0, 63 hops
    let encoded: UInt8 = 0b0011_1111
    let result = decodePathLen(encoded)

    #expect(result != nil)
    #expect(result?.hashSize == 1)
    #expect(result?.hopCount == 63, "Lower 6 bits all set = 63")
    #expect(result?.byteLength == 63, "1 * 63 = 63 bytes on wire")
  }

  // MARK: - Mode 1 (2-byte hashes)

  @Test
  func `mode 1 with 3 hops`() {
    // 0b01_000011 = mode 1, 3 hops
    let encoded: UInt8 = 0b0100_0011
    let result = decodePathLen(encoded)

    #expect(result != nil, "Mode 1 should be valid")
    #expect(result?.hashSize == 2, "Mode 1 → 2-byte hashes")
    #expect(result?.hopCount == 3)
    #expect(result?.byteLength == 6, "2 * 3 = 6 bytes on wire")
  }

  @Test
  func `mode 1 with max hops (63)`() {
    // 0b01_111111 = 0x7F
    let encoded: UInt8 = 0x7F
    let result = decodePathLen(encoded)

    #expect(result != nil)
    #expect(result?.hashSize == 2)
    #expect(result?.hopCount == 63)
    #expect(result?.byteLength == 126, "2 * 63 = 126 bytes on wire")
  }

  // MARK: - Mode 2 (3-byte hashes)

  @Test
  func `mode 2 with 4 hops`() {
    // 0b10_000100 = mode 2, 4 hops
    let encoded: UInt8 = 0b1000_0100
    let result = decodePathLen(encoded)

    #expect(result != nil, "Mode 2 should be valid")
    #expect(result?.hashSize == 3, "Mode 2 → 3-byte hashes")
    #expect(result?.hopCount == 4)
    #expect(result?.byteLength == 12, "3 * 4 = 12 bytes on wire")
  }

  @Test
  func `mode 2 with 0 hops`() {
    // 0b10_000000 = 0x80
    let encoded: UInt8 = 0x80
    let result = decodePathLen(encoded)

    #expect(result != nil)
    #expect(result?.hashSize == 3)
    #expect(result?.hopCount == 0)
    #expect(result?.byteLength == 0, "3 * 0 = 0 bytes on wire")
  }

  @Test
  func `mode 2 with max hops (63)`() {
    // 0b10_111111 = 0xBF
    let encoded: UInt8 = 0xBF
    let result = decodePathLen(encoded)

    #expect(result != nil)
    #expect(result?.hashSize == 3)
    #expect(result?.hopCount == 63)
    #expect(result?.byteLength == 189, "3 * 63 = 189 bytes on wire")
  }

  // MARK: - Mode 3 (reserved)

  @Test
  func `mode 3 returns nil`() {
    // 0b11_000001 = mode 3, 1 hop → reserved, should fail
    let encoded: UInt8 = 0b1100_0001
    let result = decodePathLen(encoded)

    #expect(result == nil, "Mode 3 (reserved) should return nil")
  }

  @Test
  func `mode 3 with zero hops returns nil`() {
    // 0b11_000000 = 0xC0
    let encoded: UInt8 = 0xC0
    let result = decodePathLen(encoded)

    #expect(result == nil, "Mode 3 should return nil regardless of hop count")
  }

  // MARK: - Flood Sentinel

  @Test
  func `0xFF flood sentinel returns nil (mode 3)`() {
    // 0xFF = 0b11_111111 = mode 3, 63 hops → reserved
    let result = decodePathLen(0xFF)

    #expect(result == nil, "0xFF (OUT_PATH_UNKNOWN flood sentinel) is mode 3 → nil")
  }

  // MARK: - Encode

  @Test
  func `encode mode 0`() {
    let encoded = encodePathLen(hashSize: 1, hopCount: 5)
    #expect(encoded == 0x05, "Mode 0, 5 hops → 0b00_000101")
  }

  @Test
  func `encode mode 1`() {
    let encoded = encodePathLen(hashSize: 2, hopCount: 10)
    #expect(encoded == 0x4A, "Mode 1, 10 hops → 0b01_001010")
  }

  @Test
  func `encode mode 2 max hops`() {
    let encoded = encodePathLen(hashSize: 3, hopCount: 63)
    #expect(encoded == 0xBF, "Mode 2, 63 hops → 0b10_111111")
  }

  @Test
  func `encode clamps hop count to 63`() {
    let encoded = encodePathLen(hashSize: 1, hopCount: 100)
    #expect(encoded == 63, "Hop count > 63 should be clamped to 63")
  }

  @Test
  func `encode/decode round-trip`() {
    for hashSize in 1...3 {
      for hopCount in [0, 1, 31, 63] {
        let encoded = encodePathLen(hashSize: hashSize, hopCount: hopCount)
        let decoded = decodePathLen(encoded)
        #expect(decoded?.hashSize == hashSize, "Round-trip hashSize for \(hashSize)/\(hopCount)")
        #expect(decoded?.hopCount == hopCount, "Round-trip hopCount for \(hashSize)/\(hopCount)")
      }
    }
  }

  // MARK: - Input Validation

  @Test(arguments: [1, 2, 3])
  func `encode accepts all valid hash sizes`(hashSize: Int) {
    // Should not trap for valid hash sizes
    let encoded = encodePathLen(hashSize: hashSize, hopCount: 1)
    let decoded = decodePathLen(encoded)
    #expect(decoded?.hashSize == hashSize)
  }

  // Note: encodePathLen(hashSize: 0) and encodePathLen(hashSize: 4+) will trap
  // via precondition. These cases are not testable without crashing the test runner.
}

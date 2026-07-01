import CryptoKit
import Foundation
@testable import MeshCore
import Testing

@Suite("TransportCodeRegionResolver")
struct TransportCodeRegionResolverTests {
  private let groupTextPayloadBits: UInt8 = 5
  private let samplePayload = Data([0x42, 0xDE, 0xAD, 0xBE, 0xEF, 0x01, 0x02, 0x03])

  // MARK: - Scope key derivation

  @Test
  func `Scope key is SHA-256 of #-prefixed name truncated to 16 bytes`() throws {
    let key = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let expected = Data(SHA256.hash(data: Data("#Germany".utf8)).prefix(16))
    #expect(key == expected)
    #expect(key.count == 16)
  }

  @Test
  func `Names with and without # produce identical scope keys`() throws {
    let a = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let b = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "#Germany"))
    #expect(a == b)
  }

  @Test
  func `Whitespace around region name is trimmed before normalization`() throws {
    let trimmed = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "  Germany  "))
    let direct = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    #expect(trimmed == direct)
  }

  @Test
  func `$-prefixed (private) region returns nil scope key`() {
    #expect(TransportCodeRegionResolver.deriveScopeKey(regionName: "$secret") == nil)
    #expect(TransportCodeRegionResolver.deriveScopeKey(regionName: "$") == nil)
  }

  @Test
  func `Empty or whitespace-only region name returns nil`() {
    #expect(TransportCodeRegionResolver.deriveScopeKey(regionName: "") == nil)
    #expect(TransportCodeRegionResolver.deriveScopeKey(regionName: "   ") == nil)
    #expect(TransportCodeRegionResolver.deriveScopeKey(regionName: "\t\n") == nil)
  }

  @Test
  func `Repeated derivation of the same name yields identical keys`() throws {
    let a = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Bavaria"))
    let b = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Bavaria"))
    #expect(a == b)
  }

  // MARK: - Reserved-boundary rewrites

  @Test
  func `rewriteReservedCode maps 0 to 1`() {
    #expect(TransportCodeRegionResolver.rewriteReservedCode(0) == 1)
  }

  @Test
  func `rewriteReservedCode maps 0xFFFF to 0xFFFE`() {
    #expect(TransportCodeRegionResolver.rewriteReservedCode(0xFFFF) == 0xFFFE)
  }

  @Test
  func `rewriteReservedCode passes through non-reserved values`() {
    #expect(TransportCodeRegionResolver.rewriteReservedCode(1) == 1)
    #expect(TransportCodeRegionResolver.rewriteReservedCode(0xFFFE) == 0xFFFE)
    #expect(TransportCodeRegionResolver.rewriteReservedCode(0x1234) == 0x1234)
    #expect(TransportCodeRegionResolver.rewriteReservedCode(0x8000) == 0x8000)
  }

  // MARK: - Transport code computation

  @Test
  func `calcTransportCode reads first two HMAC bytes as little-endian UInt16`() throws {
    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let payloadTypeBits: UInt8 = groupTextPayloadBits

    var combined = Data()
    combined.append(payloadTypeBits & 0x0F)
    combined.append(samplePayload)
    let mac = HMAC<SHA256>.authenticationCode(
      for: combined,
      using: SymmetricKey(data: scopeKey)
    )
    let macBytes = Array(mac.prefix(2))
    let expectedRaw = UInt16(macBytes[0]) | (UInt16(macBytes[1]) << 8)
    let expected = TransportCodeRegionResolver.rewriteReservedCode(expectedRaw)

    let actual = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: payloadTypeBits,
      payload: samplePayload
    )
    #expect(actual == expected)
  }

  @Test
  func `calcTransportCode masks payload type bits to low nibble`() throws {
    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let masked = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: 0x05,
      payload: samplePayload
    )
    let withHighBits = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: 0xF5,
      payload: samplePayload
    )
    #expect(masked == withHighBits)
  }

  @Test
  func `calcTransportCode is deterministic for the same inputs`() throws {
    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Bavaria"))
    let a = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey, payloadTypeBits: 5, payload: samplePayload
    )
    let b = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey, payloadTypeBits: 5, payload: samplePayload
    )
    #expect(a == b)
  }

  // MARK: - Region matching

  @Test
  func `Round-trip: compute code then resolve back to region name`() throws {
    let regionName = "Germany"
    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: regionName))
    let expectedCode = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: groupTextPayloadBits,
      payload: samplePayload
    )

    let scopeKeys: [(name: String, key: Data)] = [(regionName, scopeKey)]
    let match = TransportCodeRegionResolver.findMatchingRegion(
      scopeKeys: scopeKeys,
      expectedTransportCode0: expectedCode,
      payloadTypeBits: groupTextPayloadBits,
      payload: samplePayload
    )
    #expect(match == regionName)
  }

  @Test
  func `Empty scopeKeys returns nil`() {
    let match = TransportCodeRegionResolver.findMatchingRegion(
      scopeKeys: [],
      expectedTransportCode0: 0x1234,
      payloadTypeBits: groupTextPayloadBits,
      payload: samplePayload
    )
    #expect(match == nil)
  }

  @Test
  func `No matching region returns nil`() throws {
    let germany = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let usa = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "USA"))
    let actualCode = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: germany,
      payloadTypeBits: groupTextPayloadBits,
      payload: samplePayload
    )
    let wrongCode: UInt16 = actualCode &+ 1

    let scopeKeys: [(name: String, key: Data)] = [
      ("Germany", germany),
      ("USA", usa)
    ]
    let match = TransportCodeRegionResolver.findMatchingRegion(
      scopeKeys: scopeKeys,
      expectedTransportCode0: wrongCode,
      payloadTypeBits: groupTextPayloadBits,
      payload: samplePayload
    )
    #expect(match == nil)
  }

  @Test
  func `First match wins when iterating scopeKeys`() throws {
    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let expectedCode = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: groupTextPayloadBits,
      payload: samplePayload
    )

    // Same key listed twice under different names — first one wins.
    let scopeKeys: [(name: String, key: Data)] = [
      ("FirstName", scopeKey),
      ("SecondName", scopeKey)
    ]
    let match = TransportCodeRegionResolver.findMatchingRegion(
      scopeKeys: scopeKeys,
      expectedTransportCode0: expectedCode,
      payloadTypeBits: groupTextPayloadBits,
      payload: samplePayload
    )
    #expect(match == "FirstName")
  }
}

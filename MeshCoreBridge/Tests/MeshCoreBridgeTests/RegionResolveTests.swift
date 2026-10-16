import Foundation
import MeshCore
import Testing
@testable import MeshCoreBridgeKit

/// Freezes the flood-region resolve boundary against independently generated vectors.
///
/// Every scope key and transport code below was produced by Python's `hashlib`/`hmac` against the
/// firmware rules in `TransportKeyStore.cpp` and `RegionMap.cpp`, not by
/// `TransportCodeRegionResolver`. A fault in the code under test therefore cannot make its own
/// fixture agree with it — the numbers are simply wrong and the match fails.
struct RegionResolveTests {
  // MARK: - Vectors

  /// The caller's region list, in the caller's order. `$private` is skipped, `  Germany  ` is
  /// trimmed before hashing, and `#Bavaria` already carries the prefix the rule would add.
  static let regionNames = ["$private", "  Germany  ", "#Bavaria"]

  /// `SHA256("#Germany")[0..<16]` and `SHA256("#Bavaria")[0..<16]`, for the record. The request
  /// carries only names; these are what the bridge must derive from them.
  static let germanyScopeKey = "a5f3117485052a62a83bcd690748091f"
  static let bavariaScopeKey = "63e0562885ecda143d6c7e6300d82808"

  static let payload = hex("42deadbeef010203")
  static let groupTextBits: UInt8 = 5

  /// `HMAC-SHA256(germanyScopeKey, 0x05 || payload)[0..<2]` read little-endian: `0x7C2B`.
  static let germanyCode = hex("2b7c")
  /// The same input under Bavaria's key: `0x78D4`.
  static let bavariaCode = hex("d478")
  /// A code neither region produces.
  static let unknownCode = hex("9999")

  /// A payload whose raw MAC prefix under Germany's key is `0x0000`, found by search. The firmware
  /// rewrites the reserved value to `0x0001`, so the wire carries `01 00`.
  static let reservedPayload = hex("c0de00010e45")
  static let reservedCode = hex("0100")

  /// The seven rows the fixtures cover: a hit on each region, a code nothing matches, a transport
  /// code too short to read, a longer field whose second code is junk, the reserved-value rewrite,
  /// and a payload-type byte whose high nibble must be masked away.
  static var sampleRows: [RegionResolveCodec.Row] {
    [
      row(transportCode: germanyCode),
      row(transportCode: bavariaCode),
      row(transportCode: unknownCode),
      row(transportCode: hex("2b")),
      row(transportCode: germanyCode + hex("ffff")),
      row(payload: reservedPayload, transportCode: reservedCode),
      row(payloadTypeBits: 0xF5, transportCode: germanyCode)
    ]
  }

  static func row(
    payload: Data = payload,
    payloadTypeBits: UInt8 = groupTextBits,
    transportCode: Data
  ) -> RegionResolveCodec.Row {
    RegionResolveCodec.Row(payload: payload, payloadTypeBits: payloadTypeBits, transportCode: transportCode)
  }

  // MARK: - Tests

  @Test("the sample request encodes to its golden file, and that file decodes back")
  func requestGolden() throws {
    let bytes = Self.encode(request: Self.regionNames, rows: Self.sampleRows).serialized()
    try GoldenFixtureTests.compareOrRegenerate(bytes, file: Self.fixture("resolve-request.json"))
    let stored = try Data(contentsOf: Self.fixture("resolve-request.json"))
    let request = try RegionResolveCodec.decode(request: Array(RxLogDecryptTests.trimmingNewline(stored)))
    #expect(request.regionNames == Self.regionNames)
    #expect(request.rows.count == Self.sampleRows.count)
  }

  @Test("the sample request resolves to its golden response")
  func responseGolden() throws {
    let request = Self.encode(request: Self.regionNames, rows: Self.sampleRows).serialized()
    try GoldenFixtureTests.compareOrRegenerate(
      RegionResolveService.resolve(requestBytes: request),
      file: Self.fixture("resolve-response.json")
    )
  }

  @Test("each independently generated transport code attributes to the region that produced it")
  func codesMatchTheirRegions() {
    let keys = RegionResolveService.scopeKeys(for: Self.regionNames)
    let matched = Self.sampleRows.map { RegionResolveService.match(row: $0, scopeKeys: keys) }
    #expect(matched.map(\.uniqueName) == ["Germany", "#Bavaria", nil, nil, "Germany", "Germany", "Germany"])
    #expect(
      matched.map(\.matches) == [
        ["Germany"], ["#Bavaria"], [], [], ["Germany"], ["Germany"], ["Germany"],
      ]
    )
  }

  @Test("scope keys are derived from names below the boundary, skipping private and blank regions")
  func scopeKeyDerivation() {
    let keys = RegionResolveService.scopeKeys(for: Self.regionNames + ["", "   "])
    #expect(keys.map(\.name) == ["  Germany  ", "#Bavaria"], "a $-prefixed or blank name derives no key")
    #expect(keys[0].key.hexString == Self.germanyScopeKey)
    #expect(keys[1].key.hexString == Self.bavariaScopeKey)
  }

  @Test("two names sharing a scope key come back as an ambiguous match list")
  func sharedScopeKeyIsAmbiguous() {
    let germany = RegionResolveService.scopeKeys(for: ["Germany"])[0]
    let keys = [(name: "Germany", key: germany.key), (name: "Clone", key: germany.key)]
    let hit = RegionResolveService.match(row: Self.row(transportCode: Self.germanyCode), scopeKeys: keys)
    #expect(hit.uniqueName == nil)
    #expect(Set(hit.matches) == Set(["Clone", "Germany"]))
  }

  @Test("a malformed request answers with a failure envelope rather than trapping")
  func malformedRequestFails() {
    let text = String(decoding: RegionResolveService.resolve(requestBytes: Array("not json".utf8)), as: UTF8.self)
    #expect(text.contains("\"kind\":\"regionResolveFailure\""))
    #expect(text.contains("\"errorCode\":\(BridgeErrorCode.invalidInput.rawValue)"))
  }

  @Test("a row missing its transport code fails the whole batch")
  func rowMissingTransportCodeFails() {
    let request = """
    {"kind":"regionResolveRequest","payload":{"regionNames":[],"rows":[{"payload":"AAAA","payloadTypeBits":5}]},"v":1}
    """
    let text = String(decoding: RegionResolveService.resolve(requestBytes: Array(request.utf8)), as: UTF8.self)
    #expect(text.contains("\"kind\":\"regionResolveFailure\""))
  }

  // MARK: - Request encoder

  /// The request encoder lives in the test because Kotlin is the producer of record for this
  /// direction; this side only has to agree with it byte for byte.
  static func encode(request regionNames: [String], rows: [RegionResolveCodec.Row]) -> JSONValue {
    BridgeProtocol.envelope(
      kind: RegionResolveCodec.requestKind,
      payload: .object([
        RegionResolveCodec.regionNamesKey: .array(regionNames.map(JSONValue.string)),
        RegionResolveCodec.rowsKey: .array(rows.map(encode(row:)))
      ])
    )
  }

  private static func encode(row: RegionResolveCodec.Row) -> JSONValue {
    .object([
      "payload": .data(row.payload),
      "payloadTypeBits": .int(row.payloadTypeBits),
      "transportCode": .data(row.transportCode)
    ])
  }

  // MARK: - Helpers

  static func fixture(_ name: String) -> URL {
    GoldenFixtureTests.fixturesDir.appendingPathComponent("region").appendingPathComponent(name)
  }

  static func hex(_ text: String) -> Data { RxLogDecryptTests.hex(text) }
}

import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("RegionScopeSemantics")
struct RegionScopeSemanticsTests {
  @Test
  func `storageFields maps none unique and ambiguous`() {
    #expect(RegionScopeSemantics.storageFields(from: .none).regionScope == nil)
    #expect(RegionScopeSemantics.storageFields(from: .none).regionScopeMatches == [])

    let unique = RegionScopeSemantics.storageFields(from: .unique("Germany"))
    #expect(unique.regionScope == "Germany")
    #expect(unique.regionScopeMatches == ["Germany"])

    let ambiguous = RegionScopeSemantics.storageFields(from: .ambiguous(["de-hh", "de-by"]))
    #expect(ambiguous.regionScope == nil)
    #expect(ambiguous.regionScopeMatches == ["de-by", "de-hh"])
  }

  @Test
  func `coalesce prefers multi-match over sticky scope`() {
    let result = RegionScopeSemantics.coalesce(
      scope: "Germany",
      matches: ["de-hh", "de-by"]
    )
    #expect(result == .ambiguous(["de-by", "de-hh"]))
  }

  @Test
  func `coalesce unique from single match`() {
    #expect(
      RegionScopeSemantics.coalesce(scope: nil, matches: ["USA"]) == .unique("USA")
    )
  }

  @Test
  func `coalesce legacy unique from scope only`() {
    #expect(
      RegionScopeSemantics.coalesce(scope: "Bavaria", matches: []) == .unique("Bavaria")
    )
  }

  @Test
  func `coalesce none when both empty`() {
    #expect(RegionScopeSemantics.coalesce(scope: nil, matches: []) == .none)
    #expect(RegionScopeSemantics.coalesce(scope: "  ", matches: ["", " "]) == .none)
  }

  @Test
  func `chipLabel joins ambiguous with slash separator`() {
    #expect(RegionScopeSemantics.chipLabel(from: .none) == nil)
    #expect(RegionScopeSemantics.chipLabel(from: .unique("Germany")) == "Germany")
    #expect(
      RegionScopeSemantics.chipLabel(from: .ambiguous(["de-by", "de-hh"]))
        == "de-by / de-hh"
    )
  }

  @Test
  func `matchRegions multi-match never stores first-match as regionScope`() throws {
    let scopeKey = try #require(TransportCodeRegionResolver.deriveScopeKey(regionName: "Germany"))
    let payload = Data([0x01, 0x02, 0x03, 0x04])
    let code = TransportCodeRegionResolver.calcTransportCode(
      scopeKey: scopeKey,
      payloadTypeBits: 5,
      payload: payload
    )
    let match = TransportCodeRegionResolver.matchRegions(
      scopeKeys: [("First", scopeKey), ("Second", scopeKey)],
      expectedTransportCode0: code,
      payloadTypeBits: 5,
      payload: payload
    )
    let fields = RegionScopeSemantics.storageFields(from: match)
    #expect(fields.regionScope == nil)
    #expect(fields.regionScopeMatches.count == 2)
    #expect(Set(fields.regionScopeMatches) == Set(["First", "Second"]))
  }
}

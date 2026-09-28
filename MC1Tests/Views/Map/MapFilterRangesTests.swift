import Foundation
@testable import MC1
import Testing

@Suite("MapFilterRanges")
struct MapFilterRangesTests {
  private static let now = Date(timeIntervalSince1970: 1_800_000_000)

  private static func heard(ago seconds: TimeInterval) -> Date {
    now.addingTimeInterval(-seconds)
  }

  // MARK: - Duration

  @Test
  func `duration seconds per unit`() {
    #expect(MapDuration(15, .minutes).seconds == 900)
    #expect(MapDuration(2, .hours).seconds == 7200)
    #expect(MapDuration(3, .days).seconds == 259_200)
  }

  @Test
  func `duration clamps value into range`() {
    #expect(MapDuration(0, .hours).value == 1)
    #expect(MapDuration(-5, .hours).value == 1)
    #expect(MapDuration(100_000, .days).value == 9999)
  }

  @Test
  func `duration decode clamps stored value`() throws {
    let json = Data(#"{"value":0,"unit":"minutes"}"#.utf8)
    let decoded = try JSONDecoder().decode(MapDuration.self, from: json)
    #expect(decoded == MapDuration(1, .minutes))
  }

  // MARK: - Last Heard

  @Test
  func `any last heard range passes everything including never heard`() {
    #expect(!MapLastHeardRange.any.isActive)
    #expect(MapLastHeardRange.any.contains(heardAt: nil, now: Self.now))
    #expect(MapLastHeardRange.any.contains(heardAt: Self.heard(ago: 1_000_000), now: Self.now))
  }

  @Test
  func `max age is inclusive at the boundary`() {
    let range = MapLastHeardRange.within(MapDuration(2, .hours))
    #expect(range.contains(heardAt: Self.heard(ago: 7200), now: Self.now))
    #expect(range.contains(heardAt: Self.heard(ago: 60), now: Self.now))
    #expect(!range.contains(heardAt: Self.heard(ago: 7201), now: Self.now))
  }

  @Test
  func `min age hides recently heard nodes`() {
    let range = MapLastHeardRange(minAge: MapDuration(1, .days))
    #expect(!range.contains(heardAt: Self.heard(ago: 3600), now: Self.now))
    #expect(range.contains(heardAt: Self.heard(ago: 86400), now: Self.now))
    #expect(range.contains(heardAt: Self.heard(ago: 86400 * 30), now: Self.now))
  }

  @Test
  func `bounded range keeps only the window`() {
    let range = MapLastHeardRange(minAge: MapDuration(1, .hours), maxAge: MapDuration(6, .hours))
    #expect(!range.contains(heardAt: Self.heard(ago: 1800), now: Self.now))
    #expect(range.contains(heardAt: Self.heard(ago: 3 * 3600), now: Self.now))
    #expect(!range.contains(heardAt: Self.heard(ago: 7 * 3600), now: Self.now))
  }

  @Test
  func `active range hides never heard nodes`() {
    let range = MapLastHeardRange.within(MapDuration(7, .days))
    #expect(!range.contains(heardAt: nil, now: Self.now))
  }

  @Test
  func `future timestamps count as just heard`() {
    let future = Self.now.addingTimeInterval(3600)
    #expect(MapLastHeardRange.within(MapDuration(15, .minutes)).contains(heardAt: future, now: Self.now))
    #expect(!MapLastHeardRange(minAge: MapDuration(15, .minutes)).contains(heardAt: future, now: Self.now))
  }

  @Test
  func `setting min above max raises max`() {
    let range = MapLastHeardRange.within(MapDuration(1, .hours)).withMinAge(MapDuration(2, .days))
    #expect(range.minAge == MapDuration(2, .days))
    #expect(range.maxAge == MapDuration(2, .days))
  }

  @Test
  func `setting max below min lowers min`() {
    let range = MapLastHeardRange(minAge: MapDuration(1, .days)).withMaxAge(MapDuration(30, .minutes))
    #expect(range.minAge == MapDuration(30, .minutes))
    #expect(range.maxAge == MapDuration(30, .minutes))
  }

  @Test
  func `crossed stored range is repaired on init`() {
    let range = MapLastHeardRange(minAge: MapDuration(3, .days), maxAge: MapDuration(1, .hours))
    #expect(range.maxAge == MapDuration(3, .days))
  }

  @Test
  func `last heard presets start with any and are otherwise active`() {
    #expect(MapLastHeardRange.presets.first == .any)
    let presetsAfterAnyAreActive = MapLastHeardRange.presets.dropFirst().allSatisfy(\.isActive)
    #expect(presetsAfterAnyAreActive)
    #expect(MapLastHeardRange.presets.contains(.within(MapDuration(2, .hours))))
  }

  @Test
  func `last heard decode tolerates invalid bounds`() throws {
    let json = Data(#"{"minAge":{"value":"x"},"maxAge":{"value":2,"unit":"hours"}}"#.utf8)
    let decoded = try JSONDecoder().decode(MapLastHeardRange.self, from: json)
    #expect(decoded == .within(MapDuration(2, .hours)))
  }

  // MARK: - Hops

  @Test
  func `any hop range passes unknown`() {
    #expect(!MapHopRange.any.isActive)
    #expect(MapHopRange.any.contains(nil))
    #expect(MapHopRange.any.contains(63))
  }

  @Test
  func `direct only passes zero hops`() {
    #expect(MapHopRange.direct.contains(0))
    #expect(!MapHopRange.direct.contains(1))
    #expect(!MapHopRange.direct.contains(nil))
  }

  @Test
  func `bounded hop range is inclusive`() {
    let range = MapHopRange(min: 6, max: 10)
    #expect(!range.contains(5))
    #expect(range.contains(6))
    #expect(range.contains(10))
    #expect(!range.contains(11))
  }

  @Test
  func `min only hop range has no upper limit`() {
    let range = MapHopRange(min: 3)
    #expect(range.isActive)
    #expect(!range.contains(2))
    #expect(range.contains(63))
  }

  @Test
  func `hop bounds clamp to protocol domain`() {
    let range = MapHopRange(min: -4, max: 200)
    #expect(range.min == 0)
    #expect(range.max == nil)
    #expect(MapHopRange(min: 99).min == 63)
  }

  @Test
  func `max at ceiling collapses to no limit`() {
    #expect(MapHopRange(max: 63).max == nil)
    #expect(MapHopRange(max: 2).withMax(63) == .any)
  }

  @Test
  func `setting hop min above max raises max`() {
    let range = MapHopRange(max: 2).withMin(5)
    #expect(range.min == 5)
    #expect(range.max == 5)
  }

  @Test
  func `setting hop max below min lowers min`() {
    let range = MapHopRange(min: 8).withMax(3)
    #expect(range.min == 3)
    #expect(range.max == 3)
  }

  @Test
  func `hop presets`() {
    #expect(MapHopRange.presets == [.any, .direct, MapHopRange(max: 1), MapHopRange(max: 2), MapHopRange(max: 3)])
  }

  @Test
  func `hop decode clamps and defaults missing min`() throws {
    let json = Data(#"{"max":500}"#.utf8)
    let decoded = try JSONDecoder().decode(MapHopRange.self, from: json)
    #expect(decoded == .any)
  }
}

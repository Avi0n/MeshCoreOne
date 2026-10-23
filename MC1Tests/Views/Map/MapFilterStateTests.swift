import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing

@Suite("MapFilterState")
struct MapFilterStateTests {
  @Test
  func `struct defaults match design`() {
    let s = MapFilterState()
    #expect(s.favoritesOnly == false)
    #expect(s.showDiscovered == false)
    #expect(s.showChat && s.showRepeater && s.showRoom)
  }

  @Test
  func `tracePath host seed turns discovered on`() {
    let s = MapFilterState.seed(for: .tracePath)
    #expect(s.showDiscovered == true)
    #expect(s.favoritesOnly == false)
  }

  @Test
  func `setFavoritesOnly freezes type and discovered fields`() {
    var s = MapFilterState(showDiscovered: true, showChat: true, showRepeater: false, showRoom: true)
    s.setFavoritesOnly(true)
    #expect(s.favoritesOnly)
    #expect(s.showDiscovered == true)
    #expect(s.showRepeater == false)
    s.setShowDiscovered(false) // no-op while favorites
    s.setShowChat(false, host: .mainMap)
    #expect(s.showDiscovered == true)
    #expect(s.showChat == true)
    s.setFavoritesOnly(false)
    #expect(!s.favoritesOnly)
    #expect(s.showDiscovered == true)
    #expect(s.showRepeater == false)
  }

  @Test
  func `cannot clear last enabled type in capabilities`() {
    var s = MapFilterState(showChat: true, showRepeater: false, showRoom: false)
    s.setShowChat(false, host: .mainMap)
    #expect(s.showChat == true)
  }

  @Test
  func `sanitized repairs all types off when types in caps`() {
    let raw = MapFilterState(
      favoritesOnly: false,
      showDiscovered: true,
      showChat: false,
      showRepeater: false,
      showRoom: false
    )
    let s = raw.sanitized(for: .mainMap)
    #expect(s.showChat && s.showRepeater && s.showRoom)
  }

  @Test
  func `empty storageString init returns nil`() {
    #expect(MapFilterState(storageString: "") == nil)
  }

  @Test
  func `cannot clear last enabled repeater or room type`() {
    var repeaterOnly = MapFilterState(showChat: false, showRepeater: true, showRoom: false)
    repeaterOnly.setShowRepeater(false, host: .mainMap)
    #expect(repeaterOnly.showRepeater == true)

    var roomOnly = MapFilterState(showChat: false, showRepeater: false, showRoom: true)
    roomOnly.setShowRoom(false, host: .mainMap)
    #expect(roomOnly.showRoom == true)
  }

  @Test
  func `type setters are no-ops on hosts without type capabilities`() {
    var s = MapFilterState(showChat: true, showRepeater: true, showRoom: true)
    s.setShowChat(false, host: .tracePath)
    s.setShowRepeater(false, host: .neighborSNR)
    #expect(s.showChat && s.showRepeater && s.showRoom)
  }

  @Test
  func `preferences binding round trips host encode`() {
    var raw = ""
    let binding = MapFilterPreferences.binding(
      raw: Binding(get: { raw }, set: { raw = $0 }),
      host: .mainMap
    )
    #expect(binding.wrappedValue == MapFilterState.seed(for: .mainMap).sanitized(for: .mainMap))
    var next = binding.wrappedValue
    next.setShowDiscovered(true)
    binding.wrappedValue = next
    #expect(!raw.isEmpty)
    #expect(MapFilterPreferences.state(fromRaw: raw, host: .mainMap).showDiscovered)
  }

  @Test
  func `json string round trip`() throws {
    let original = MapFilterState(
      favoritesOnly: true,
      showDiscovered: true,
      showChat: false,
      showRepeater: true,
      showRoom: true
    )
    let encoded = original.storageString
    let decoded = try #require(MapFilterState(storageString: encoded))
    #expect(decoded == original)
  }

  @Test
  func `differsFromSeed relative to host defaults`() {
    let defaults = MapFilterState.seed(for: .mainMap)
    #expect(!defaults.differsFromSeed(for: .mainMap))
    var s = defaults
    s.setShowDiscovered(true)
    #expect(s.differsFromSeed(for: .mainMap))
  }

  @Test
  func `differsFromSeed relative to TracePath seed`() {
    let defaults = MapFilterState.seed(for: .tracePath)
    #expect(!defaults.differsFromSeed(for: .tracePath))
    var s = defaults
    s.setShowDiscovered(false)
    #expect(s.differsFromSeed(for: .tracePath))
  }

  @Test
  func `differsFromSeed for favorites and type toggles`() {
    let defaults = MapFilterState.seed(for: .mainMap)
    #expect(!defaults.differsFromSeed(for: .mainMap))

    var favorites = defaults
    favorites.setFavoritesOnly(true)
    #expect(favorites.differsFromSeed(for: .mainMap))

    var noChat = defaults
    noChat.setShowChat(false, host: .mainMap)
    #expect(noChat.differsFromSeed(for: .mainMap))

    var noRepeater = defaults
    noRepeater.setShowRepeater(false, host: .mainMap)
    #expect(noRepeater.differsFromSeed(for: .mainMap))

    var noRoom = defaults
    noRoom.setShowRoom(false, host: .mainMap)
    #expect(noRoom.differsFromSeed(for: .mainMap))
  }

  @Test
  func `effectiveShowDiscovered false while favorites`() {
    var s = MapFilterState(showDiscovered: true)
    s.setFavoritesOnly(true)
    #expect(s.effectiveShowDiscovered == false)
    #expect(s.showDiscovered == true)
  }

  @Test
  func `host storage keys match AppStorageKey raw values`() {
    #expect(MapFilterHost.mainMap.storageKey == AppStorageKey.mapFilterMainMap.rawValue)
    #expect(MapFilterHost.tracePath.storageKey == AppStorageKey.mapFilterTracePath.rawValue)
    #expect(MapFilterHost.neighborSNR.storageKey == AppStorageKey.mapFilterNeighborSNR.rawValue)
  }

  @Test
  func `allowsContactType honors type flags when not favorites`() {
    var s = MapFilterState(showChat: false, showRepeater: true, showRoom: true)
    #expect(!s.allowsContactType(.chat))
    #expect(s.allowsContactType(.repeater))
    s.setFavoritesOnly(true)
    #expect(s.allowsContactType(.chat))
  }

  // MARK: - Advanced Filters

  @Test
  func `legacy json without advanced keys decodes with defaults`() throws {
    let legacy = #"{"favoritesOnly":false,"showDiscovered":true,"showChat":true,"showRepeater":false,"showRoom":true}"#
    let decoded = try #require(MapFilterState(storageString: legacy))
    #expect(decoded.showDiscovered)
    #expect(!decoded.showRepeater)
    #expect(decoded.lastHeard == .any)
    #expect(decoded.hops == .any)
  }

  @Test
  func `json missing every key decodes to struct defaults`() throws {
    let decoded = try #require(MapFilterState(storageString: "{}"))
    #expect(decoded == MapFilterState())
  }

  @Test
  func `invalid nested advanced values fall back to any without losing toggles`() throws {
    let raw = #"{"favoritesOnly":true,"lastHeard":"bogus","hops":{"min":"x"}}"#
    let decoded = try #require(MapFilterState(storageString: raw))
    #expect(decoded.favoritesOnly)
    #expect(decoded.lastHeard == .any)
    #expect(decoded.hops == .any)
  }

  @Test
  func `advanced json round trip`() throws {
    let original = MapFilterState(
      showDiscovered: true,
      lastHeard: MapLastHeardRange(minAge: MapDuration(30, .minutes), maxAge: MapDuration(2, .days)),
      hops: MapHopRange(min: 6, max: 10)
    )
    let decoded = try #require(MapFilterState(storageString: original.storageString))
    #expect(decoded == original)
  }

  @Test
  func `advanced filters mark main map as differing from seed`() {
    var s = MapFilterState.seed(for: .mainMap)
    s.setLastHeard(.within(MapDuration(2, .hours)))
    #expect(s.differsFromSeed(for: .mainMap))
    #expect(s.activeAdvancedDimensions(for: .mainMap) == [.lastHeard])
    s.setHops(.direct)
    #expect(s.activeAdvancedDimensions(for: .mainMap) == [.lastHeard, .hops])
  }

  @Test
  func `clear removes one dimension and reset removes all`() {
    var s = MapFilterState(lastHeard: .within(MapDuration(1, .hours)), hops: .direct)
    s.clear(.lastHeard)
    #expect(s.lastHeard == .any)
    #expect(s.hops == .direct)
    s.setLastHeard(.within(MapDuration(1, .hours)))
    s.resetAdvanced()
    #expect(!s.hasActiveAdvancedFilters(for: .mainMap))
    #expect(!s.differsFromSeed(for: .mainMap))
  }

  @Test(arguments: [MapFilterHost.tracePath, .neighborSNR])
  func `hosts without advanced capability strip stored ranges`(host: MapFilterHost) {
    var s = MapFilterState.seed(for: host)
    s.setLastHeard(.within(MapDuration(1, .hours)))
    s.setHops(.direct)
    #expect(s.activeAdvancedDimensions(for: host).isEmpty)
    let sanitized = s.sanitized(for: host)
    #expect(sanitized.lastHeard == .any)
    #expect(sanitized.hops == .any)
    #expect(!sanitized.differsFromSeed(for: host))
  }

  @Test
  func `main map keeps advanced ranges through sanitize`() {
    let s = MapFilterState(lastHeard: .within(MapDuration(6, .hours)), hops: MapHopRange(max: 2))
    let sanitized = s.sanitized(for: .mainMap)
    #expect(sanitized.lastHeard == s.lastHeard)
    #expect(sanitized.hops == s.hops)
  }
}

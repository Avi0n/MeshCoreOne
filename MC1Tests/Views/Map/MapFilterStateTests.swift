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
}

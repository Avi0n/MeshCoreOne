import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MapFilterPreferences migration")
struct MapFilterMigrationTests {
  @Test
  func `legacy only seeds mainMap discovered and writes new key`() throws {
    let suiteName = "mapFilter.migrate.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    suite.set(true, forKey: AppStorageKey.showDiscoveredNodesOnMap.rawValue)
    #expect(suite.string(forKey: AppStorageKey.mapFilterMainMap.rawValue) == nil)

    let outcome = MapFilterPreferences.resolveMigrating(host: .mainMap, from: suite)
    #expect(outcome.state.showDiscovered == true)
    #expect(outcome.didWrite)
    #expect(suite.string(forKey: AppStorageKey.mapFilterMainMap.rawValue) != nil)

    // Second load does not re-read legacy over a user change.
    var edited = outcome.state
    edited.setShowDiscovered(false)
    suite.set(
      MapFilterPreferences.encode(edited, host: .mainMap),
      forKey: AppStorageKey.mapFilterMainMap.rawValue
    )
    let again = MapFilterPreferences.resolveMigrating(host: .mainMap, from: suite)
    #expect(again.state.showDiscovered == false)
    #expect(!again.didWrite)
  }

  @Test
  func `existing mapFilter wins over legacy true`() throws {
    let suiteName = "mapFilter.dual.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    suite.set(true, forKey: AppStorageKey.showDiscoveredNodesOnMap.rawValue)
    let stored = MapFilterState(showDiscovered: false)
    suite.set(stored.storageString, forKey: AppStorageKey.mapFilterMainMap.rawValue)

    let outcome = MapFilterPreferences.resolveMigrating(host: .mainMap, from: suite)
    #expect(outcome.state.showDiscovered == false)
    #expect(!outcome.didWrite)
  }

  @Test
  func `tracePath missing key seeds discovered true without writing`() throws {
    let suiteName = "mapFilter.trace.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    let outcome = MapFilterPreferences.resolveMigrating(host: .tracePath, from: suite)
    #expect(outcome.state.showDiscovered == true)
    #expect(!outcome.didWrite)
    #expect(suite.object(forKey: AppStorageKey.mapFilterTracePath.rawValue) == nil)
  }

  @Test
  func `neighborSNR missing key seeds discovered false without writing`() throws {
    let suiteName = "mapFilter.neighbor.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    let outcome = MapFilterPreferences.resolveMigrating(host: .neighborSNR, from: suite)
    #expect(outcome.state.showDiscovered == false)
    #expect(outcome.state.favoritesOnly == false)
    #expect(!outcome.didWrite)
    #expect(suite.object(forKey: AppStorageKey.mapFilterNeighborSNR.rawValue) == nil)
  }

  @Test
  func `pure seed load leaves mainMap key absent`() throws {
    let suiteName = "mapFilter.pureSeed.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    let outcome = MapFilterPreferences.resolveMigrating(host: .mainMap, from: suite)
    #expect(outcome.state == MapFilterState.seed(for: .mainMap).sanitized(for: .mainMap))
    #expect(!outcome.didWrite)
    #expect(suite.object(forKey: AppStorageKey.mapFilterMainMap.rawValue) == nil)
  }

  @Test
  func `corrupt stored JSON falls back to seed via missing decode`() throws {
    let suiteName = "mapFilter.corrupt.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    suite.set("{not-json", forKey: AppStorageKey.mapFilterMainMap.rawValue)
    // Invalid string fails init?(storageString:), so resolveMigrating re-seeds and overwrites.
    let outcome = MapFilterPreferences.resolveMigrating(host: .mainMap, from: suite)
    #expect(
      outcome.state == MapFilterState.seed(for: .mainMap)
        .sanitized(for: .mainMap)
    )
    #expect(outcome.didWrite)
    #expect(suite.string(forKey: AppStorageKey.mapFilterMainMap.rawValue) != nil)
  }

  @Test
  func `legacy false seeds mainMap discovered off`() throws {
    let suiteName = "mapFilter.legacyFalse.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    suite.set(false, forKey: AppStorageKey.showDiscoveredNodesOnMap.rawValue)
    let outcome = MapFilterPreferences.resolveMigrating(host: .mainMap, from: suite)
    #expect(outcome.state.showDiscovered == false)
    #expect(outcome.didWrite)
    #expect(suite.string(forKey: AppStorageKey.mapFilterMainMap.rawValue) != nil)
  }

  @Test
  func `ensureMigrated leaves empty raw when pure seed`() throws {
    let suiteName = "mapFilter.ensureEmpty.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    var raw = ""
    MapFilterPreferences.ensureMigrated(raw: &raw, host: .mainMap, defaults: suite)
    #expect(raw.isEmpty)
    #expect(suite.object(forKey: AppStorageKey.mapFilterMainMap.rawValue) == nil)
  }

  @Test
  func `ensureMigrated assigns raw when legacy mainMap present`() throws {
    let suiteName = "mapFilter.ensureLegacy.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    suite.set(true, forKey: AppStorageKey.showDiscoveredNodesOnMap.rawValue)
    var raw = ""
    MapFilterPreferences.ensureMigrated(raw: &raw, host: .mainMap, defaults: suite)
    #expect(!raw.isEmpty)
    let state = try #require(MapFilterState(storageString: raw))
    #expect(state.showDiscovered == true)
  }

  /// Pure seed leaves the key unwritten; a later legacy-only import still migrates when re-run.
  @Test
  func `ensureMigrated after pure seed then legacy restore seeds discovered`() throws {
    let suiteName = "mapFilter.ensurePostRestore.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    var raw = ""
    MapFilterPreferences.ensureMigrated(raw: &raw, host: .mainMap, defaults: suite)
    #expect(raw.isEmpty)
    #expect(suite.object(forKey: AppStorageKey.mapFilterMainMap.rawValue) == nil)

    suite.set(true, forKey: AppStorageKey.showDiscoveredNodesOnMap.rawValue)
    MapFilterPreferences.ensureMigrated(raw: &raw, host: .mainMap, defaults: suite)
    #expect(!raw.isEmpty)
    let state = try #require(MapFilterState(storageString: raw))
    #expect(state.showDiscovered == true)
  }

  @Test
  func `ensureMigrated leaves valid raw unchanged`() throws {
    let suiteName = "mapFilter.ensureValid.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    let original = MapFilterState(
      showDiscovered: true,
      showChat: false,
      showRepeater: true,
      showRoom: true
    ).storageString
    var raw = original
    MapFilterPreferences.ensureMigrated(raw: &raw, host: .mainMap, defaults: suite)
    #expect(raw == original)
    #expect(suite.object(forKey: AppStorageKey.mapFilterMainMap.rawValue) == nil)
  }

  @Test
  func `ensureMigrated rewrites all types off`() throws {
    let suiteName = "mapFilter.ensureSanitize.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    let corrupt = MapFilterState(showChat: false, showRepeater: false, showRoom: false)
    var raw = corrupt.storageString
    MapFilterPreferences.ensureMigrated(raw: &raw, host: .mainMap, defaults: suite)
    let repaired = try #require(MapFilterState(storageString: raw))
    #expect(repaired.showChat && repaired.showRepeater && repaired.showRoom)
    #expect(suite.string(forKey: AppStorageKey.mapFilterMainMap.rawValue) == raw)
  }

  @Test
  func `state from empty raw equals host seed`() {
    let main = MapFilterPreferences.state(fromRaw: "", host: .mainMap)
    #expect(main == MapFilterState.seed(for: .mainMap).sanitized(for: .mainMap))
    let trace = MapFilterPreferences.state(fromRaw: "", host: .tracePath)
    #expect(trace == MapFilterState.seed(for: .tracePath).sanitized(for: .tracePath))
  }

  @Test
  func `encode sanitizes all types off`() throws {
    let raw = MapFilterState(showChat: false, showRepeater: false, showRoom: false)
    let encoded = MapFilterPreferences.encode(raw, host: .mainMap)
    let decoded = try #require(MapFilterState(storageString: encoded))
    #expect(decoded.showChat && decoded.showRepeater && decoded.showRoom)
  }

  @Test
  func `legacy false does not override TracePath seed discovered true`() throws {
    let suiteName = "mapFilter.legacyTrace.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    // Trace Path ignores main-map legacy; seed keeps Discovered on.
    suite.set(false, forKey: AppStorageKey.showDiscoveredNodesOnMap.rawValue)
    let outcome = MapFilterPreferences.resolveMigrating(host: .tracePath, from: suite)
    #expect(outcome.state.showDiscovered == true)
    #expect(!outcome.didWrite)
  }

  @Test
  func `legacy true does not seed NeighborSNR discovered on`() throws {
    let suiteName = "mapFilter.legacyNeighbor.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    suite.set(true, forKey: AppStorageKey.showDiscoveredNodesOnMap.rawValue)
    let outcome = MapFilterPreferences.resolveMigrating(host: .neighborSNR, from: suite)
    #expect(outcome.state.showDiscovered == false)
    #expect(!outcome.didWrite)
  }

  @Test
  func `stored all types off is sanitized on load and rewritten`() throws {
    let suiteName = "mapFilter.allTypesOff.\(UUID().uuidString)"
    let suite = try #require(UserDefaults(suiteName: suiteName))
    defer { suite.removePersistentDomain(forName: suiteName) }

    let corrupt = MapFilterState(
      showChat: false,
      showRepeater: false,
      showRoom: false
    )
    suite.set(corrupt.storageString, forKey: AppStorageKey.mapFilterMainMap.rawValue)
    let outcome = MapFilterPreferences.resolveMigrating(host: .mainMap, from: suite)
    #expect(outcome.state.showChat && outcome.state.showRepeater && outcome.state.showRoom)
    #expect(outcome.didWrite)
    let rewritten = MapFilterState(storageString: suite.string(forKey: AppStorageKey.mapFilterMainMap.rawValue) ?? "")
    #expect(rewritten?.showChat == true)
  }
}

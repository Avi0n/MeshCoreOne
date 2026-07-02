import Foundation
@testable import MC1Services
import SwiftData
import Testing

@Suite("ChannelFloodScope corrective migration", .serialized)
struct ChannelFloodScopeMigrationTests {
  // MARK: - Helpers

  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  /// Inserts a Channel model with raw fields — simulates a row persisted by an older
  /// app version (before `floodScopeModeRawValue` existed, so every row was "inherit"
  /// regardless of the per-channel region override).
  private func insertLegacyChannel(
    into store: PersistenceStore,
    radioID: UUID,
    index: UInt8,
    regionScope: String?
  ) async throws {
    let inheritRaw = ChannelFloodScopeStorage.Mode.inherit.rawValue
    let dto = ChannelDTO(
      id: UUID(),
      radioID: radioID,
      index: index,
      name: "Chan\(index)",
      secret: Data(repeating: 0, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      notificationLevel: .all,
      isFavorite: false,
      floodScopeModeRawValue: inheritRaw,
      regionScope: regionScope
    )
    try await store.saveChannel(dto)
  }

  // MARK: - Migration behavior

  @Test
  func `Non-nil regionScope with inherit mode is promoted to .specific`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    // UserDefaults is thread-safe but not marked Sendable, so reusing this value
    // across the performChannelFloodScopeMigration actor boundary needs the isolation opt-out.
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let store = try await createTestStore()
    let radioID = UUID()
    try await insertLegacyChannel(into: store, radioID: radioID, index: 1, regionScope: "Germany")

    try await store.performChannelFloodScopeMigration(defaults: defaults)

    let channels = try await store.fetchChannels(radioID: radioID)
    #expect(channels.first?.floodScope == .region("Germany"))
  }

  @Test
  func `Nil regionScope with inherit mode stays at .inherit (corrective)`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let store = try await createTestStore()
    let radioID = UUID()
    try await insertLegacyChannel(into: store, radioID: radioID, index: 1, regionScope: nil)

    try await store.performChannelFloodScopeMigration(defaults: defaults)

    let channels = try await store.fetchChannels(radioID: radioID)
    #expect(channels.first?.floodScope == .inherit)
  }

  @Test
  func `Mixed rows migrate correctly in one pass`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let store = try await createTestStore()
    let radioID = UUID()
    try await insertLegacyChannel(into: store, radioID: radioID, index: 1, regionScope: nil)
    try await insertLegacyChannel(into: store, radioID: radioID, index: 2, regionScope: "Germany")
    try await insertLegacyChannel(into: store, radioID: radioID, index: 3, regionScope: "France")

    try await store.performChannelFloodScopeMigration(defaults: defaults)

    let channels = try await store.fetchChannels(radioID: radioID).sorted { $0.index < $1.index }
    #expect(channels.map(\.floodScope) == [.inherit, .region("Germany"), .region("France")])
  }

  @Test
  func `Migration is idempotent — second run is a no-op`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let store = try await createTestStore()
    let radioID = UUID()
    try await insertLegacyChannel(into: store, radioID: radioID, index: 1, regionScope: "Germany")

    try await store.performChannelFloodScopeMigration(defaults: defaults)
    // Second run with the flag set must not touch anything.
    try await store.performChannelFloodScopeMigration(defaults: defaults)

    let channels = try await store.fetchChannels(radioID: radioID)
    #expect(channels.first?.floodScope == .region("Germany"))
  }

  @Test
  func `Post-migration writes to .allRegions are not clobbered by a re-run`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let store = try await createTestStore()
    let radioID = UUID()
    // A channel the user explicitly set to .allRegions after upgrading.
    let dto = ChannelDTO.testChannel(radioID: radioID, index: 1, floodScope: .allRegions)
    try await store.saveChannel(dto)

    try await store.performChannelFloodScopeMigration(defaults: defaults)

    let channels = try await store.fetchChannels(radioID: radioID)
    #expect(channels.first?.floodScope == .allRegions)
  }
}

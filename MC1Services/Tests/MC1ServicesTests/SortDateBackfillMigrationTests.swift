import Foundation
@testable import MC1Services
import SwiftData
import Testing

@Suite("Message sortDate backfill migration", .serialized)
struct SortDateBackfillMigrationTests {
  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  @Test
  func `Pre-existing rows get sortDate backfilled to createdAt`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    // UserDefaults is thread-safe but not marked Sendable, so reusing this value
    // across the performSortDateBackfillMigration actor boundary needs the isolation opt-out.
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let store = try await createTestStore()
    let radioID = UUID()

    let firstID = UUID()
    let firstCreatedAt = Date(timeIntervalSince1970: 1_704_067_200)
    try await store.insertMessageWithSortDate(
      id: firstID,
      radioID: radioID,
      text: "Hello",
      createdAt: firstCreatedAt,
      sortDate: .distantPast
    )

    let secondID = UUID()
    let secondCreatedAt = Date(timeIntervalSince1970: 1_704_070_800)
    try await store.insertMessageWithSortDate(
      id: secondID,
      radioID: radioID,
      text: "World",
      createdAt: secondCreatedAt,
      sortDate: .distantPast
    )

    try await store.performSortDateBackfillMigration(defaults: defaults)

    let messages = try await store.fetchAllMessages()
    #expect(messages.count == 2)
    for message in messages {
      #expect(message.sortDate == message.createdAt)
    }
  }

  @Test
  func `Migration is idempotent — second run is a no-op`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    nonisolated(unsafe) let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let store = try await createTestStore()
    let radioID = UUID()

    let messageID = UUID()
    let createdAt = Date(timeIntervalSince1970: 1_704_067_200)
    try await store.insertMessageWithSortDate(
      id: messageID,
      radioID: radioID,
      text: "Hello",
      createdAt: createdAt,
      sortDate: .distantPast
    )

    try await store.performSortDateBackfillMigration(defaults: defaults)

    let backfilled = try await store.fetchMessage(id: messageID)
    #expect(backfilled?.sortDate == createdAt)

    // Re-skew the row after the first run. A second call with the flag set must
    // not touch it — otherwise the backfill would re-run every launch.
    try await store.setMessageSortDate(id: messageID, sortDate: .distantPast)
    try await store.performSortDateBackfillMigration(defaults: defaults)

    let afterSecondRun = try await store.fetchMessage(id: messageID)
    #expect(afterSecondRun?.sortDate == .distantPast, "second run must be a no-op")
  }
}

import Foundation
@testable import MC1Services
import SwiftData
import Testing

/// Time-based retention with a hard row ceiling for persisted debug logs:
/// the prune keeps entries inside the window, drops entries older than it,
/// and enforces the ceiling even when the window alone would exceed it.
@Suite("PersistenceStore debug log retention")
struct DebugLogRetentionPruneTests {
  private func createTestStore() throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func entry(age: TimeInterval) -> DebugLogEntryDTO {
    DebugLogEntryDTO(
      timestamp: Date().addingTimeInterval(-age),
      level: .info,
      subsystem: "test",
      category: "retention",
      message: "entry aged \(age)s"
    )
  }

  @Test
  func `prune keeps a recent entry and drops one older than the window`() async throws {
    let store = try createTestStore()
    let recent = entry(age: 60)
    let stale = entry(age: DebugLogRetention.window + 3600)
    try await store.saveDebugLogEntries([recent, stale])

    try await store.pruneDebugLogEntries(
      olderThan: Date().addingTimeInterval(-DebugLogRetention.window),
      keepCount: DebugLogRetention.maxEntries
    )

    let remaining = try await store.fetchDebugLogEntries(since: .distantPast, limit: 10)
    #expect(remaining.map(\.id) == [recent.id])
  }

  @Test
  func `prune enforces the ceiling when the window alone exceeds it`() async throws {
    let store = try createTestStore()
    // All entries are inside the window; only the ceiling can drop any.
    let entries = (0..<10).map { entry(age: TimeInterval($0) * 60) }
    try await store.saveDebugLogEntries(entries)

    try await store.pruneDebugLogEntries(
      olderThan: Date().addingTimeInterval(-DebugLogRetention.window),
      keepCount: 4
    )

    let remaining = try await store.fetchDebugLogEntries(since: .distantPast, limit: 20)
    #expect(remaining.count == 4)
    // The newest entries survive; the oldest are the ones deleted.
    #expect(Set(remaining.map(\.id)) == Set(entries.prefix(4).map(\.id)))
  }
}

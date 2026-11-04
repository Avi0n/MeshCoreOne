import Foundation
@testable import MC1Services
import Testing

/// Serialized because each test reassigns `DebugLogBuffer.shared`, which `ServiceContainer.init` also reassigns.
/// A read right after a write can see another suite's buffer, so assertions retry.
@Suite("DebugLogBuffer Tests", .serialized)
struct DebugLogBufferTests {
  private static let maxReadBackAttempts = 5
  /// `pruneIfDue` stamps cutoff with `Date()` at call time; the test reads it a tick later.
  private static let pruneCutoffSlack: TimeInterval = 2

  /// On-path tests pin `collectDebugLogs` true. `record` drops entries when the key is missing.
  private static let collectionKey = AppStorageKey.collectDebugLogs.rawValue

  private func enableCollectionForTesting() -> Any? {
    let captured = UserDefaults.standard.object(forKey: Self.collectionKey)
    UserDefaults.standard.set(true, forKey: Self.collectionKey)
    return captured
  }

  private func restoreCollection(_ captured: Any?) {
    if let captured {
      UserDefaults.standard.set(captured, forKey: Self.collectionKey)
    } else {
      UserDefaults.standard.removeObject(forKey: Self.collectionKey)
    }
  }

  private func makeBuffer() async throws -> DebugLogBuffer {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    return DebugLogBuffer(dataStore: store)
  }

  /// Assigns `buffer` to `DebugLogBuffer.shared` and reads it back. Retries because another suite can reassign the global in between.
  private func writeAndReadBack(_ buffer: DebugLogBuffer, attempts: Int = maxReadBackAttempts) -> Bool {
    for _ in 0..<attempts {
      DebugLogBuffer.shared = buffer
      if DebugLogBuffer.shared === buffer {
        return true
      }
    }
    return false
  }

  @Test
  func `shared get and set round-trip through the lock`() async throws {
    let buffer = try await makeBuffer()
    #expect(writeAndReadBack(buffer))
    DebugLogBuffer.shared = nil
  }

  /// Concurrent `shared` access stays race-free under the lock. `ServiceContainer` reassigns it while loggers read it.
  @Test
  func `concurrent reads and writes of shared are race-free`() async throws {
    let bufferA = try await makeBuffer()
    let bufferB = try await makeBuffer()
    DebugLogBuffer.shared = bufferA

    await withTaskGroup(of: Void.self) { group in
      for index in 0..<200 {
        group.addTask {
          if index.isMultiple(of: 2) {
            DebugLogBuffer.shared = index.isMultiple(of: 4) ? bufferA : bufferB
          } else {
            _ = DebugLogBuffer.shared
          }
        }
      }
    }

    #expect(writeAndReadBack(bufferA))

    DebugLogBuffer.shared = nil
  }

  // MARK: - Pending buffer (entries logged before `shared` is assigned)

  private enum TestStoreFailure: Error {
    case saveFailed
  }

  /// Flushes until `category` has `minimumCount` entries, covering the delay before a drain `Task` runs.
  private func pollForDebugLogEntries(
    _ store: MockPersistenceStore,
    buffer: DebugLogBuffer,
    category: String,
    minimumCount: Int,
    attempts: Int = 30,
    delay: Duration = .milliseconds(30)
  ) async -> [DebugLogEntryDTO] {
    for _ in 0..<attempts {
      await buffer.flush()
      let matches = await store.debugLogEntries.filter { $0.category == category }
      if matches.count >= minimumCount {
        return matches
      }
      try? await Task.sleep(for: delay)
    }
    return await store.debugLogEntries.filter { $0.category == category }
  }

  /// Entries logged before `shared` is assigned drain in order when it is set. Retries because another suite can steal the global.
  @Test
  func `entries logged before shared is assigned are drained in order once a buffer is set`() async {
    let captured = enableCollectionForTesting()
    defer { restoreCollection(captured) }
    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)

    var entries: [DebugLogEntryDTO] = []
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      let logger = PersistentLogger(subsystem: "test.pending", category: category)
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()
      logger.info("first")
      logger.info("second")

      guard writeAndReadBack(buffer) else { continue }
      entries = await pollForDebugLogEntries(store, buffer: buffer, category: category, minimumCount: 2)
      if entries.count >= 2 { break }
    }
    #expect(entries.map(\.message) == ["first", "second"])

    DebugLogBuffer.shared = nil
  }

  /// Past `maxPendingEntries`, the pending queue drops the oldest so the no-buffer window stays bounded.
  @Test
  func `pending queue drops oldest entries once the bound is exceeded`() async {
    let captured = enableCollectionForTesting()
    defer { restoreCollection(captured) }
    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)
    let overflow = 3
    let total = DebugLogBuffer.maxPendingEntries + overflow

    var entries: [DebugLogEntryDTO] = []
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()
      for index in 0..<total {
        DebugLogBuffer.record(
          DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "\(index)")
        )
      }

      guard writeAndReadBack(buffer) else { continue }
      entries = await pollForDebugLogEntries(
        store, buffer: buffer, category: category, minimumCount: DebugLogBuffer.maxPendingEntries
      )
      if entries.count >= DebugLogBuffer.maxPendingEntries { break }
    }
    #expect(entries.count == DebugLogBuffer.maxPendingEntries)
    #expect(entries.first?.message == "\(overflow)")
    #expect(entries.last?.message == "\(total - 1)")

    DebugLogBuffer.shared = nil
  }

  // MARK: - Save-failure visibility

  /// Parks saves until `releaseWaiters`, so two flushes can fail together and one hits the backlog cap.
  private actor GatedDebugLogStore: DebugLogPersisting {
    private(set) var savedEntries: [DebugLogEntryDTO] = []
    private var shouldFail = true
    private var failNextSummarySave = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    var pendingSaveCount: Int {
      waiters.count
    }

    /// Captures fail-or-succeed before parking, so flipping `shouldFail` after release does not change the parked calls.
    func saveDebugLogEntries(_ entries: [DebugLogEntryDTO]) async throws {
      let willFail = shouldFail
      if willFail {
        await withCheckedContinuation { waiters.append($0) }
        throw TestStoreFailure.saveFailed
      }
      if failNextSummarySave, entries.count == 1 {
        failNextSummarySave = false
        throw TestStoreFailure.saveFailed
      }
      savedEntries.append(contentsOf: entries)
    }

    func fetchDebugLogEntries(since: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
      []
    }

    func countDebugLogEntries() async throws -> Int {
      savedEntries.count
    }

    func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {}
    func clearDebugLogEntries() async throws {}

    func releaseWaiters() {
      let pending = waiters
      waiters = []
      pending.forEach { $0.resume() }
    }

    func setShouldFail(_ value: Bool) {
      shouldFail = value
    }

    /// Fails the next one-entry save. The drop summary is saved alone; batch saves still succeed.
    func setFailNextSummarySave(_ value: Bool) {
      failNextSummarySave = value
    }
  }

  /// Holds two full-buffer flushes at the save gate and releases them while the store is failing. One batch hits the backlog cap.
  private func accumulateDroppedBatch(store: GatedDebugLogStore, buffer: DebugLogBuffer) async throws {
    for index in 0..<DebugLogBuffer.maxBufferSize {
      await buffer.append(DebugLogEntryDTO(level: .info, subsystem: "test", category: "a", message: "a\(index)"))
    }
    for index in 0..<DebugLogBuffer.maxBufferSize {
      await buffer.append(DebugLogEntryDTO(level: .info, subsystem: "test", category: "b", message: "b\(index)"))
    }

    try await waitUntil("both size-triggered flushes should park at the save gate") {
      await store.pendingSaveCount >= 2
    }
    #expect(await store.pendingSaveCount == 2)

    await store.releaseWaiters()
    await store.setShouldFail(false)
  }

  /// One of the two failing batches fills the backlog, so the other is dropped entirely. Recovery then reports that loss.
  @Test
  func `dropped entries during save failures are reported once the store recovers`() async throws {
    let captured = enableCollectionForTesting()
    defer { restoreCollection(captured) }
    let store = GatedDebugLogStore()
    let buffer = DebugLogBuffer(dataStore: store)

    try await accumulateDroppedBatch(store: store, buffer: buffer)

    try await waitUntil("recovery flush should write a dropped-count summary") {
      await buffer.flush()
      let entries = await store.savedEntries
      return entries.contains(where: { $0.level == .warning })
    }
    let saved = await store.savedEntries

    let realEntries = saved.filter { $0.level != .warning }
    #expect(realEntries.count == DebugLogBuffer.maxBufferSize)

    let summary = saved.first { $0.category == "DebugLogBuffer" && $0.level == .warning }
    #expect(summary != nil)
    #expect(summary?.message.contains("\(DebugLogBuffer.maxBufferSize)") == true)
  }

  /// A failed summary save keeps the dropped count, and the next successful save reports it in full.
  @Test
  func `a failed summary save carries the dropped count forward to the next successful save`() async throws {
    let captured = enableCollectionForTesting()
    defer { restoreCollection(captured) }
    let store = GatedDebugLogStore()
    let buffer = DebugLogBuffer(dataStore: store)

    try await accumulateDroppedBatch(store: store, buffer: buffer)
    await store.setFailNextSummarySave(true)

    // First recovery flush saves the surviving batch; its summary save fails once.
    try await waitUntil("first recovery flush should persist the surviving batch") {
      await buffer.flush()
      let entries = await store.savedEntries
      return entries.count(where: { $0.level != .warning }) >= DebugLogBuffer.maxBufferSize
    }
    var saved = await store.savedEntries
    #expect(saved.filter { $0.level == .warning }.isEmpty)

    // The next successful save must report the carried-forward count in full.
    await buffer.append(DebugLogEntryDTO(level: .info, subsystem: "test", category: "c", message: "post-recovery"))
    try await waitUntil("next successful save should report the carried-forward drop count") {
      await buffer.flush()
      let entries = await store.savedEntries
      return entries.contains(where: { $0.level == .warning })
    }
    saved = await store.savedEntries

    let summary = saved.first { $0.category == "DebugLogBuffer" && $0.level == .warning }
    #expect(summary != nil)
    #expect(summary?.message.contains("\(DebugLogBuffer.maxBufferSize)") == true)
  }

  // MARK: - Hourly prune

  private actor RecordingDebugLogStore: DebugLogPersisting {
    private enum PruneFailure: Error {
      case simulated
    }

    private(set) var pruneCallCount = 0
    private(set) var lastPruneCutoff: Date?
    private(set) var lastPruneKeepCount: Int?
    private var pruneShouldFail = false

    func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {}

    func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
      []
    }

    func countDebugLogEntries() async throws -> Int {
      0
    }

    func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {
      pruneCallCount += 1
      lastPruneCutoff = cutoff
      lastPruneKeepCount = keepCount
      if pruneShouldFail {
        throw PruneFailure.simulated
      }
    }

    func setPruneShouldFail(_ value: Bool) {
      pruneShouldFail = value
    }

    func clearDebugLogEntries() async throws {}
  }

  private func expectWindowBackedPrune(_ store: RecordingDebugLogStore) async {
    #expect(await store.lastPruneKeepCount == DebugLogRetention.maxEntries)
    let cutoff = await store.lastPruneCutoff
    #expect(cutoff != nil)
    guard let cutoff else { return }
    let expected = Date().addingTimeInterval(-DebugLogRetention.window)
    #expect(abs(cutoff.timeIntervalSince(expected)) < Self.pruneCutoffSlack)
  }

  @Test
  func `hourly prune is skipped when last prune is within pruneInterval`() async {
    let captured = enableCollectionForTesting()
    defer { restoreCollection(captured) }
    let store = RecordingDebugLogStore()
    let buffer = DebugLogBuffer(dataStore: store)
    await buffer.setLastPruneForTesting(Date())
    await buffer.append(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "prune", message: "recent")
    )
    await buffer.flush()
    #expect(await store.pruneCallCount == 0)
    #expect(await store.lastPruneCutoff == nil)
    #expect(await store.lastPruneKeepCount == nil)
  }

  @Test
  func `hourly prune runs when last prune is older than pruneInterval`() async {
    let captured = enableCollectionForTesting()
    defer { restoreCollection(captured) }
    let store = RecordingDebugLogStore()
    let buffer = DebugLogBuffer(dataStore: store)
    await buffer.setLastPruneForTesting(Date().addingTimeInterval(-DebugLogRetention.pruneInterval))
    await buffer.append(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "prune", message: "stale")
    )
    await buffer.flush()
    #expect(await store.pruneCallCount == 1)
    await expectWindowBackedPrune(store)
  }

  @Test
  func `hourly prune retries on the next flush after a failed prune`() async {
    let captured = enableCollectionForTesting()
    defer { restoreCollection(captured) }
    let store = RecordingDebugLogStore()
    await store.setPruneShouldFail(true)
    let buffer = DebugLogBuffer(dataStore: store)
    await buffer.setLastPruneForTesting(Date().addingTimeInterval(-DebugLogRetention.pruneInterval))
    await buffer.append(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "prune", message: "first")
    )
    await buffer.flush()
    #expect(await store.pruneCallCount == 1)
    await expectWindowBackedPrune(store)

    await buffer.append(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "prune", message: "second")
    )
    await buffer.flush()
    #expect(await store.pruneCallCount == 2)
    await expectWindowBackedPrune(store)
  }
}

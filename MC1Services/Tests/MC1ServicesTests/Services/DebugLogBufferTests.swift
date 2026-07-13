import Foundation
@testable import MC1Services
import Testing

/// Serialized because both tests reassign the process-global DebugLogBuffer.shared,
/// so running them in parallel breaks each other's assertions on that global.
/// `ServiceContainer.init` also reassigns this same global, and other test suites
/// that build a `ServiceContainer` run concurrently with this suite, so a read
/// right after a write can observe a foreign buffer written in between. Assertions
/// below read back through a bounded retry to absorb that interleaving.
@Suite("DebugLogBuffer Tests", .serialized)
struct DebugLogBufferTests {
  private static let maxReadBackAttempts = 5

  private func makeBuffer() async throws -> DebugLogBuffer {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    return DebugLogBuffer(dataStore: store)
  }

  /// Writes `buffer` to the global and reads it back, retrying because a
  /// concurrently running suite may reassign the global between the write and
  /// the read. Succeeds as soon as one attempt reads back the value just
  /// written; only fails if every attempt observes a foreign buffer instead.
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

  /// Reassigning `shared` per connection while loggers read it from arbitrary
  /// actors is the data race this guards against; the lock must make
  /// concurrent reads and writes safe (verified under the thread sanitizer).
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

  /// Repeatedly flushes `buffer` and re-checks `store` for entries in `category`,
  /// absorbing the delay before a fire-and-forget drain or append `Task` runs.
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

  /// `PersistentLogger.persist` queues entries logged before `shared` is assigned
  /// instead of dropping them; assigning `shared` drains that queue into the new
  /// buffer in the order entries were logged. The whole reset-log-assign sequence
  /// retries with a fresh category per attempt because a foreign suite's
  /// `ServiceContainer.init` can reassign the global mid-sequence and swallow the
  /// pending entries into its own buffer.
  @Test
  func `entries logged before shared is assigned are drained in order once a buffer is set`() async {
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

  /// Beyond `maxPendingEntries`, the pending queue drops the oldest entries first,
  /// so the window it protects (early launch, state restoration, background
  /// relaunch) can't grow the queue without bound.
  @Test
  func `pending queue drops oldest entries once the bound is exceeded`() async {
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

  /// A `DebugLogPersisting` fake whose saves block on a gate until released. This
  /// lets a test hold two size-triggered flushes in flight at once (rather than
  /// racing the buffer's fire-and-forget flush scheduling with a plain toggle),
  /// so both fail together and the second observably hits the backlog cap.
  private actor GatedDebugLogStore: DebugLogPersisting {
    private(set) var savedEntries: [DebugLogEntryDTO] = []
    private var shouldFail = true
    private var failNextSummarySave = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    var pendingSaveCount: Int {
      waiters.count
    }

    /// Captures the fail/succeed decision before gating, not after resuming, so a
    /// test can flip `shouldFail` back to false right after releasing without that
    /// change racing the still-suspended calls it just released.
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

    /// Resumes every save currently blocked on the gate.
    func releaseWaiters() {
      let pending = waiters
      waiters = []
      pending.forEach { $0.resume() }
    }

    func setShouldFail(_ value: Bool) {
      shouldFail = value
    }

    /// Fails the next single-entry save (the drop summary is always saved alone)
    /// while letting multi-entry batch saves through.
    func setFailNextSummarySave(_ value: Bool) {
      failNextSummarySave = value
    }
  }

  /// Drives two size-triggered flushes into the gated store and releases them while
  /// it is still failing, leaving the buffer with `maxBufferSize` surviving entries
  /// and a dropped count of `maxBufferSize` (one batch always loses to the backlog
  /// cap). Returns once both saves have been held at the gate.
  private func accumulateDroppedBatch(store: GatedDebugLogStore, buffer: DebugLogBuffer) async throws {
    for index in 0..<DebugLogBuffer.maxBufferSize {
      await buffer.append(DebugLogEntryDTO(level: .info, subsystem: "test", category: "a", message: "a\(index)"))
    }
    for index in 0..<DebugLogBuffer.maxBufferSize {
      await buffer.append(DebugLogEntryDTO(level: .info, subsystem: "test", category: "b", message: "b\(index)"))
    }

    for _ in 0..<100 {
      if await store.pendingSaveCount >= 2 { break }
      try await Task.sleep(for: .milliseconds(20))
    }
    #expect(await store.pendingSaveCount == 2)

    await store.releaseWaiters()
    await store.setShouldFail(false)
  }

  /// Two size-triggered flushes of `maxBufferSize` entries each are held at the
  /// save gate simultaneously, then released together while the store is still
  /// failing. Whichever settles its catch block second finds the other's full
  /// requeue already occupying the backlog (`buffer.count == maxBufferSize`), so
  /// `buffer.count + entriesToRequeue.count` reaches the `maxBufferSize * 2` cap and
  /// its entire batch is dropped — deterministic regardless of processing order,
  /// since exactly one of the two batches always loses. Once the store recovers,
  /// the surviving `maxBufferSize` entries save successfully and a summary entry
  /// reports the other batch as lost.
  @Test
  func `dropped entries during save failures are reported once the store recovers`() async throws {
    let store = GatedDebugLogStore()
    let buffer = DebugLogBuffer(dataStore: store)

    try await accumulateDroppedBatch(store: store, buffer: buffer)

    var saved: [DebugLogEntryDTO] = []
    for _ in 0..<100 {
      await buffer.flush()
      saved = await store.savedEntries
      if saved.contains(where: { $0.level == .warning }) { break }
      try? await Task.sleep(for: .milliseconds(20))
    }

    let realEntries = saved.filter { $0.level != .warning }
    #expect(realEntries.count == DebugLogBuffer.maxBufferSize)

    let summary = saved.first { $0.category == "DebugLogBuffer" && $0.level == .warning }
    #expect(summary != nil)
    #expect(summary?.message.contains("\(DebugLogBuffer.maxBufferSize)") == true)
  }

  /// A failed summary save must not reset the dropped count: the loss carries
  /// forward and the next successful save reports it in full.
  @Test
  func `a failed summary save carries the dropped count forward to the next successful save`() async throws {
    let store = GatedDebugLogStore()
    let buffer = DebugLogBuffer(dataStore: store)

    try await accumulateDroppedBatch(store: store, buffer: buffer)
    await store.setFailNextSummarySave(true)

    // First recovery flush saves the surviving batch; its summary save fails once.
    var saved: [DebugLogEntryDTO] = []
    for _ in 0..<100 {
      await buffer.flush()
      saved = await store.savedEntries
      if saved.count(where: { $0.level != .warning }) >= DebugLogBuffer.maxBufferSize { break }
      try? await Task.sleep(for: .milliseconds(20))
    }
    #expect(saved.filter { $0.level == .warning }.isEmpty)

    // The next successful save must report the carried-forward count in full.
    await buffer.append(DebugLogEntryDTO(level: .info, subsystem: "test", category: "c", message: "post-recovery"))
    for _ in 0..<100 {
      await buffer.flush()
      saved = await store.savedEntries
      if saved.contains(where: { $0.level == .warning }) { break }
      try? await Task.sleep(for: .milliseconds(20))
    }

    let summary = saved.first { $0.category == "DebugLogBuffer" && $0.level == .warning }
    #expect(summary != nil)
    #expect(summary?.message.contains("\(DebugLogBuffer.maxBufferSize)") == true)
  }
}

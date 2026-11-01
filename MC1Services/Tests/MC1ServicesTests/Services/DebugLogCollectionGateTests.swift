import Foundation
@testable import MC1Services
import os
import Testing

/// Serialized because every test reassigns the process-global
/// `DebugLogBuffer.shared` and drives the process-global
/// `UserDefaults.standard` collection key, both of which other suites (and
/// `ServiceContainer.init`) can interleave on. As in `DebugLogBufferTests`,
/// assertions read back through a bounded retry to absorb a foreign suite
/// reassigning the global mid-sequence.
///
/// The gate reads `UserDefaults.standard`, so every test captures the current
/// value for the key in setup, drives it through `UserDefaults.standard` itself,
/// and restores the captured value in teardown. A `UserDefaults(suiteName:)`
/// instance would be a different domain and never move the gate.
@Suite("DebugLogBuffer collection gate", .serialized)
struct DebugLogCollectionGateTests {
  private static let key = AppStorageKey.collectDebugLogs.rawValue
  private static let maxReadBackAttempts = 5

  /// Captures the current standard-defaults value for the collection key so
  /// teardown can restore it, leaving the process domain as the test found it.
  private struct CapturedKey {
    let value: Any?
    init() {
      value = UserDefaults.standard.object(forKey: DebugLogCollectionGateTests.key)
    }

    func restore() {
      if let value {
        UserDefaults.standard.set(value, forKey: DebugLogCollectionGateTests.key)
      } else {
        UserDefaults.standard.removeObject(forKey: DebugLogCollectionGateTests.key)
      }
    }
  }

  /// Writes `buffer` to the global and reads it back, retrying because a
  /// concurrently running suite may reassign the global between the write and the
  /// read. Succeeds as soon as one attempt reads back the value just written.
  private func writeAndReadBack(_ buffer: DebugLogBuffer, attempts: Int = maxReadBackAttempts) -> Bool {
    for _ in 0..<attempts {
      DebugLogBuffer.shared = buffer
      if DebugLogBuffer.shared === buffer {
        return true
      }
    }
    return false
  }

  /// Flushes `buffer` and returns entries in `category` from `store`. A short poll
  /// absorbs the delay before a fire-and-forget append `Task` runs; the off-path
  /// assertions read the store again after a brief sleep so a stray entry would
  /// surface rather than slip through on timing.
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

  /// Key absent (fresh install and upgrade from any prior version): `record` drops
  /// the entry, assigning a buffer cannot drain it, and a flush leaves the store
  /// empty.
  @Test
  func `missing key means off so record drops the entry and the store stays empty`() async {
    let captured = CapturedKey()
    defer { captured.restore() }
    UserDefaults.standard.removeObject(forKey: Self.key)
    #expect(UserDefaults.standard.object(forKey: Self.key) == nil)

    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)
    let category = UUID().uuidString
    DebugLogBuffer.shared = nil
    DebugLogBuffer.resetPendingStateForTesting()
    DebugLogBuffer.record(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "dropped")
    )
    #expect(writeAndReadBack(buffer))
    await buffer.flush()
    try? await Task.sleep(for: .milliseconds(50))
    let matches = await store.debugLogEntries.filter { $0.category == category }
    #expect(matches.isEmpty)
    DebugLogBuffer.shared = nil
  }

  /// Key true: an `.info` entry recorded through `PersistentLogger` persists.
  @Test
  func `collection on persists an info entry`() async {
    let captured = CapturedKey()
    defer { captured.restore() }
    UserDefaults.standard.set(true, forKey: Self.key)
    #expect(UserDefaults.standard.bool(forKey: Self.key))

    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)
    var messages: [String] = []
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()
      guard writeAndReadBack(buffer) else { continue }
      let logger = PersistentLogger(subsystem: "test.gate", category: category)
      logger.info("info-line")
      let entries = await pollForDebugLogEntries(store, buffer: buffer, category: category, minimumCount: 1)
      messages = entries.map(\.message)
      if messages.contains("info-line") { break }
    }
    DebugLogBuffer.shared = nil
    #expect(messages.contains("info-line"))
  }

  /// Key true, entry recorded before any buffer exists: queued as usual, so the
  /// on path is unchanged.
  @Test
  func `collection on queues an entry recorded before a buffer exists`() async {
    let captured = CapturedKey()
    defer { captured.restore() }
    UserDefaults.standard.set(true, forKey: Self.key)
    #expect(UserDefaults.standard.bool(forKey: Self.key))

    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)
    var entries: [DebugLogEntryDTO] = []
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()
      DebugLogBuffer.record(
        DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "queued")
      )
      guard writeAndReadBack(buffer) else { continue }
      entries = await pollForDebugLogEntries(store, buffer: buffer, category: category, minimumCount: 1)
      if !entries.isEmpty { break }
    }
    DebugLogBuffer.shared = nil
    #expect(entries.map(\.message) == ["queued"])
  }

  /// Key false: `record` drops the entry, assigns nothing, and the store stays
  /// empty.
  @Test
  func `collection off drops the entry and the store stays empty`() async {
    let captured = CapturedKey()
    defer { captured.restore() }
    UserDefaults.standard.set(false, forKey: Self.key)
    #expect(UserDefaults.standard.object(forKey: Self.key) != nil)

    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)
    let category = UUID().uuidString
    DebugLogBuffer.shared = nil
    DebugLogBuffer.resetPendingStateForTesting()
    DebugLogBuffer.record(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "dropped")
    )
    #expect(writeAndReadBack(buffer))
    await buffer.flush()
    try? await Task.sleep(for: .milliseconds(50))
    let matches = await store.debugLogEntries.filter { $0.category == category }
    #expect(matches.isEmpty)
    DebugLogBuffer.shared = nil
  }

  /// The off transition: seed rows (on), disable, assert rows zero AND the key
  /// false AND that a re-enable with a fresh buffer flushed to the same store
  /// still finds it empty (buffers drained, nothing carried over).
  @Test
  func `disableCollection clears rows, sets key false, and drains buffers`() async throws {
    let captured = CapturedKey()
    defer { captured.restore() }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let category = UUID().uuidString

    // Seed persisted rows with collection on.
    UserDefaults.standard.set(true, forKey: Self.key)
    #expect(UserDefaults.standard.bool(forKey: Self.key))
    let seed = DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "seed")
    try await store.saveDebugLogEntries([seed])
    #expect(try await store.countDebugLogEntries() == 1)

    // Also leave an unflushed in-memory entry so the buffer drain is exercised.
    let buffer = DebugLogBuffer(dataStore: store)
    DebugLogBuffer.shared = buffer
    await buffer.append(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "unflushed")
    )
    DebugLogBuffer.record(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "pending")
    )

    try await DebugLogBuffer.disableCollection(persistenceStore: store)

    #expect(UserDefaults.standard.bool(forKey: Self.key) == false)
    #expect(try await store.countDebugLogEntries() == 0)

    // Re-enable, assign a fresh buffer backed by the same store, flush: the
    // drained buffers must not have carried anything over.
    UserDefaults.standard.set(true, forKey: Self.key)
    DebugLogBuffer.shared = nil
    DebugLogBuffer.resetPendingStateForTesting()
    let buffer2 = DebugLogBuffer(dataStore: store)
    #expect(writeAndReadBack(buffer2))
    await buffer2.flush()
    try? await Task.sleep(for: .milliseconds(50))
    #expect(try await store.countDebugLogEntries() == 0)
    DebugLogBuffer.shared = nil
  }

  /// A throwing `clearDebugLogEntries` rethrows out of `disableCollection`.
  /// At the throw the key is false and the rows remain. The depth is back to
  /// zero, so a later turn-on can write the key; the view test owns that restore.
  @Test
  func `disableCollection rethrows a store failure with the key already false`() async throws {
    let captured = CapturedKey()
    defer { captured.restore() }

    struct ClearFailure: Error {}

    UserDefaults.standard.set(true, forKey: Self.key)
    #expect(UserDefaults.standard.bool(forKey: Self.key))

    let store = MockPersistenceStore()
    try await store.saveDebugLogEntries([
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "gate", message: "seed")
    ])
    #expect(await store.debugLogEntries.count == 1)
    await store.setStubbedDebugLogError(ClearFailure())

    await #expect(throws: ClearFailure.self) {
      try await DebugLogBuffer.disableCollection(persistenceStore: store)
    }
    #expect(UserDefaults.standard.bool(forKey: Self.key) == false)
    #expect(await store.debugLogEntries.count == 1)
    DebugLogBuffer.enableCollection()
    #expect(UserDefaults.standard.bool(forKey: Self.key))
  }

  /// Key already false: `append`, `flush`, and `shutdown` do not publish.
  @Test
  func `append flush and shutdown leave the store unchanged when collection is off`() async throws {
    let captured = CapturedKey()
    defer { captured.restore() }
    UserDefaults.standard.set(false, forKey: Self.key)

    let store = MockPersistenceStore()
    try await store.saveDebugLogEntries([
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "off", message: "seed")
    ])
    let buffer = DebugLogBuffer(dataStore: store)
    await buffer.append(
      DebugLogEntryDTO(level: .info, subsystem: "test", category: "off", message: "dropped")
    )
    await buffer.flush()
    await buffer.shutdown()

    let messages = await store.debugLogEntries.map(\.message)
    #expect(messages == ["seed"])
  }

  @Test
  func `disableCollection waits out a held save and leaves the store empty`() async throws {
    try await assertDisableWaitsOutHeldSave(releaseSucceeds: true)
  }

  @Test
  func `disableCollection waits out a failing save and a later flush adds nothing`() async throws {
    try await assertDisableWaitsOutHeldSave(releaseSucceeds: false)
  }

  /// Parks `saveDebugLogEntries`, starts `disableCollection`, and asserts it has
  /// not returned while the save is held. `releaseSucceeds` chooses whether the
  /// parked save then commits or throws.
  private func assertDisableWaitsOutHeldSave(releaseSucceeds: Bool) async throws {
    let captured = CapturedKey()
    defer {
      captured.restore()
      DebugLogBuffer.shared = nil
    }

    for _ in 0..<Self.maxReadBackAttempts {
      UserDefaults.standard.set(true, forKey: Self.key)
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()

      let store = HoldingDebugLogStore()
      await store.seed(
        DebugLogEntryDTO(level: .info, subsystem: "test", category: "hold", message: "seed")
      )
      let buffer = DebugLogBuffer(dataStore: store)
      guard writeAndReadBack(buffer) else { continue }

      await buffer.append(
        DebugLogEntryDTO(level: .info, subsystem: "test", category: "hold", message: "inflight")
      )
      let flushTask = Task { await buffer.flush() }
      do {
        try await waitUntil("save should park at the gate") {
          await store.heldSaveCount >= 1
        }
      } catch {
        await store.releaseHeldSaves(.succeed)
        _ = await flushTask.value
        throw error
      }
      guard DebugLogBuffer.shared === buffer else {
        await store.releaseHeldSaves(.succeed)
        _ = await flushTask.value
        continue
      }

      let finished = OSAllocatedUnfairLock(initialState: false)
      let disableTask = Task {
        try await DebugLogBuffer.disableCollection(persistenceStore: store)
        finished.withLock { $0 = true }
      }

      do {
        try await waitUntil("disable should take the key false while the save is held") {
          let keyIsOff = UserDefaults.standard.bool(forKey: Self.key) == false
          let saveIsHeld = await store.heldSaveCount >= 1
          return keyIsOff && saveIsHeld
        }
      } catch {
        await store.releaseHeldSaves(.succeed)
        _ = try? await disableTask.value
        _ = await flushTask.value
        throw error
      }

      try await Task.sleep(for: .milliseconds(50))
      guard DebugLogBuffer.shared === buffer else {
        await store.releaseHeldSaves(.succeed)
        _ = try? await disableTask.value
        _ = await flushTask.value
        continue
      }

      let returnedWhileHeld = finished.withLock { $0 }
      let stillHeld = await store.heldSaveCount >= 1
      let clearedWhileHeld = await store.clearCount > 0

      let release: HoldingDebugLogStore.Release = releaseSucceeds ? .succeed : .fail
      await store.releaseHeldSaves(release)
      try await disableTask.value
      _ = await flushTask.value

      #expect(returnedWhileHeld == false)
      #expect(stillHeld)
      #expect(clearedWhileHeld == false)
      #expect(UserDefaults.standard.bool(forKey: Self.key) == false)
      #expect(try await store.countDebugLogEntries() == 0)

      await buffer.flush()
      await buffer.shutdown()
      #expect(try await store.countDebugLogEntries() == 0)
      return
    }

    Issue.record("shared buffer was replaced before disable could wait out the save")
  }

  /// A `DebugLogPersisting` fake whose saves park on a gate. The park is an
  /// `await`, so `clearDebugLogEntries` can reenter the actor while a save is
  /// held — the same window a suspended `saveDebugLogEntries` leaves open.
  private actor HoldingDebugLogStore: DebugLogPersisting {
    enum Release: Sendable {
      case succeed
      case fail
    }

    struct SaveFailure: Error {}

    private var entries: [DebugLogEntryDTO] = []
    private var saveWaiters: [CheckedContinuation<Release, Never>] = []
    private(set) var clearCount = 0

    var heldSaveCount: Int {
      saveWaiters.count
    }

    func seed(_ entry: DebugLogEntryDTO) {
      entries.append(entry)
    }

    func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws {
      let release = await withCheckedContinuation { continuation in
        saveWaiters.append(continuation)
      }
      switch release {
      case .succeed:
        entries.append(contentsOf: dtos)
      case .fail:
        throw SaveFailure()
      }
    }

    func releaseHeldSaves(_ release: Release) {
      let waiters = saveWaiters
      saveWaiters.removeAll()
      for waiter in waiters {
        waiter.resume(returning: release)
      }
    }

    func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
      []
    }

    func countDebugLogEntries() async throws -> Int {
      entries.count
    }

    func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {}

    func clearDebugLogEntries() async throws {
      clearCount += 1
      entries.removeAll()
    }
  }
}

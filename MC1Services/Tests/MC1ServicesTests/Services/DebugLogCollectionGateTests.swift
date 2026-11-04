import Foundation
@testable import MC1Services
import os
import Testing

/// Serialized because each test reassigns `DebugLogBuffer.shared` and the process `collectDebugLogs` key.
/// Other suites write that key, so a missing or stored-false attempt retries until the value holds across `record`.
@Suite("DebugLogBuffer collection gate", .serialized)
struct DebugLogCollectionGateTests {
  private static let key = AppStorageKey.collectDebugLogs.rawValue
  private static let maxReadBackAttempts = 5

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
  func `missing key means off so record drops the entry and the store stays empty`() async {
    let captured = CapturedKey()
    defer { captured.restore() }

    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)
    var stayedEmpty = false
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      UserDefaults.standard.removeObject(forKey: Self.key)
      guard UserDefaults.standard.object(forKey: Self.key) == nil else { continue }
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()
      DebugLogBuffer.record(
        DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "dropped")
      )
      guard UserDefaults.standard.object(forKey: Self.key) == nil, writeAndReadBack(buffer) else { continue }
      await buffer.flush()
      try? await Task.sleep(for: .milliseconds(50))
      let matches = await store.debugLogEntries.filter { $0.category == category }
      stayedEmpty = matches.isEmpty
      if stayedEmpty { break }
    }
    DebugLogBuffer.shared = nil
    #expect(stayedEmpty)
  }

  /// A stored false is off, distinct from a missing key.
  @Test
  func `collection off drops the entry and the store stays empty`() async {
    let captured = CapturedKey()
    defer { captured.restore() }

    let store = MockPersistenceStore()
    let buffer = DebugLogBuffer(dataStore: store)
    var stayedEmpty = false
    for _ in 0..<Self.maxReadBackAttempts {
      let category = UUID().uuidString
      UserDefaults.standard.set(false, forKey: Self.key)
      guard UserDefaults.standard.object(forKey: Self.key) != nil,
            UserDefaults.standard.bool(forKey: Self.key) == false else { continue }
      DebugLogBuffer.shared = nil
      DebugLogBuffer.resetPendingStateForTesting()
      DebugLogBuffer.record(
        DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "dropped")
      )
      guard UserDefaults.standard.bool(forKey: Self.key) == false, writeAndReadBack(buffer) else { continue }
      await buffer.flush()
      try? await Task.sleep(for: .milliseconds(50))
      let matches = await store.debugLogEntries.filter { $0.category == category }
      stayedEmpty = matches.isEmpty
      if stayedEmpty { break }
    }
    DebugLogBuffer.shared = nil
    #expect(stayedEmpty)
  }

  @Test
  func `disableCollection clears rows, sets key false, and drains buffers`() async throws {
    let captured = CapturedKey()
    defer { captured.restore() }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let category = UUID().uuidString

    UserDefaults.standard.set(true, forKey: Self.key)
    #expect(UserDefaults.standard.bool(forKey: Self.key))
    let seed = DebugLogEntryDTO(level: .info, subsystem: "test", category: category, message: "seed")
    try await store.saveDebugLogEntries([seed])
    #expect(try await store.countDebugLogEntries() == 1)

    // Unflushed memory and a `record` still in flight must not survive the wipe.
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

    // A later buffer on the same store must not publish what disable dropped.
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

  /// At the throw the key is false and the rows remain. The view turns the key back on after this returns.
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

  /// Parks `saveDebugLogEntries`, then starts `disableCollection`. `releaseSucceeds` chooses whether the parked save commits or throws.
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

  /// Parks `saveDebugLogEntries` on an await, so `clearDebugLogEntries` can reenter while a save is held.
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

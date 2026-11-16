import Foundation
import os

/// Actor for buffering debug log entries and flushing to persistence.
/// Provides batched saves for performance and backpressure handling.
public actor DebugLogBuffer {
  /// `record` and the `shared` setter share this lock, so an entry reaches the current buffer or waits in `pending`.
  private struct SharedState {
    var buffer: DebugLogBuffer?
    var pending: [DebugLogEntryDTO] = []
  }

  private static let sharedState = OSAllocatedUnfairLock<SharedState>(initialState: SharedState())
  /// In-progress `disableCollection` calls. The off write and `enableCollection` share this lock, so a turn-on during the wipe cannot land true.
  private struct CollectionDisable {
    var depth = 0
  }

  private static let collectionDisable = OSAllocatedUnfairLock(initialState: CollectionDisable())
  static let maxPendingEntries = 500

  /// Missing `collectDebugLogs` reads as off. Checked on every `record` from `UserDefaults.standard`, the domain `@AppStorage` writes.
  nonisolated static var isCollectionEnabled: Bool {
    UserDefaults.standard.bool(forKey: AppStorageKey.collectDebugLogs.rawValue)
  }

  /// Reassigned from `ServiceContainer.init` while `PersistentLogger` records from other actors. Assignment drains `pending` in record order.
  public static var shared: DebugLogBuffer? {
    get { sharedState.withLock { $0.buffer } }
    set {
      let drained = sharedState.withLock { state -> [DebugLogEntryDTO] in
        state.buffer = newValue
        guard newValue != nil else { return [] }
        defer { state.pending = [] }
        return state.pending
      }
      guard let newValue, !drained.isEmpty else { return }
      Task {
        for entry in drained {
          await newValue.append(entry)
        }
      }
    }
  }

  /// Hands `entry` to the shared buffer, or queues it (oldest dropped past the bound) until one is assigned.
  /// Drops it when collection is off. Only `disableCollection` discards entries already queued.
  static func record(_ entry: DebugLogEntryDTO) {
    let buffer = sharedState.withLock { state -> DebugLogBuffer? in
      guard isCollectionEnabled else { return nil }
      if let buffer = state.buffer { return buffer }
      state.pending.append(entry)
      if state.pending.count > maxPendingEntries {
        state.pending.removeFirst(state.pending.count - maxPendingEntries)
      }
      return nil
    }
    guard let buffer else { return }
    Task { await buffer.append(entry) }
  }

  /// Turns collection on unless `disableCollection` is in progress, which leaves the key false.
  public static func enableCollection() {
    let allowed = collectionDisable.withLock { $0.depth == 0 }
    guard allowed else { return }
    UserDefaults.standard.set(true, forKey: AppStorageKey.collectDebugLogs.rawValue)
    collectionDisable.withLock { state in
      guard state.depth > 0 else { return }
      UserDefaults.standard.set(false, forKey: AppStorageKey.collectDebugLogs.rawValue)
    }
  }

  /// Sets the key false and holds it until this returns, including on throw.
  /// Waits out an in-flight `saveDebugLogEntries`, drops memory, then deletes rows, so that save cannot finish after the delete.
  /// A throw leaves the key false and the rows; the caller turns the key back on.
  public static func disableCollection(persistenceStore: any DebugLogPersisting) async throws {
    beginCollectionDisable()
    defer { endCollectionDisable() }
    let buffer = sharedState.withLock { state -> DebugLogBuffer? in
      state.pending = []
      return state.buffer
    }
    if let buffer {
      await buffer.relinquishForDisable()
    }
    try await persistenceStore.clearDebugLogEntries()
  }

  private static func beginCollectionDisable() {
    collectionDisable.withLock { state in
      state.depth += 1
      UserDefaults.standard.set(false, forKey: AppStorageKey.collectDebugLogs.rawValue)
    }
  }

  private static func endCollectionDisable() {
    collectionDisable.withLock { state in
      state.depth -= 1
    }
  }

  /// Empties `pending` so a test starts from the no-buffer window.
  static func resetPendingStateForTesting() {
    sharedState.withLock { $0.pending = [] }
  }

  private let dataStore: any DebugLogPersisting
  private var buffer: [DebugLogEntryDTO] = []
  private var flushTask: Task<Void, Never>?
  private var isFlushScheduled = false
  private let flushInterval: Duration = .seconds(5)
  static let maxBufferSize = 50
  /// Seeded to now so the first successful flush after connect does not
  /// repeat the connect-time `pruneDebugLogEntries` on this store.
  private var lastPrune = Date()

  /// Entries lost since the last successful save. The next successful save reports them, because that window is otherwise invisible.
  private var droppedEntryCount = 0

  /// `flushBuffer` can overlap itself through `flushNow`. Two successes must not persist two drop summaries.
  private var isPersistingDropSummary = false

  /// `flushBuffer` calls past the collection gate that have not returned.
  /// `disableCollection` waits for zero before deleting rows, so an in-flight save cannot finish after the delete.
  private var persistsInFlight = 0
  private var persistIdleWaiters: [CheckedContinuation<Void, Never>] = []

  private static let logSubsystem = "com.mc1"
  private static let logCategory = "DebugLogBuffer"
  private static let logger = Logger(subsystem: logSubsystem, category: logCategory)

  public init(dataStore: any DebugLogPersisting) {
    self.dataStore = dataStore
  }

  public func append(_ entry: DebugLogEntryDTO) {
    guard Self.isCollectionEnabled else { return }
    buffer.append(entry)

    if buffer.count >= Self.maxBufferSize {
      flushNow()
    } else {
      scheduleFlush()
    }
  }

  public func flush() async {
    flushTask?.cancel()
    flushTask = nil
    isFlushScheduled = false
    await flushBuffer()
  }

  public func shutdown() async {
    flushTask?.cancel()
    flushTask = nil
    isFlushScheduled = false
    await flushBuffer()
  }

  /// Discards unflushed entries and cancels the scheduled flush without saving.
  public func discardPendingEntries() {
    flushTask?.cancel()
    flushTask = nil
    isFlushScheduled = false
    buffer = []
  }

  /// Waits out in-flight saves, then drops memory. The key is already false, so a flush started during the wait does not publish.
  private func relinquishForDisable() async {
    flushTask?.cancel()
    flushTask = nil
    isFlushScheduled = false
    await waitForPersistsToIdle()
    discardPendingEntries()
    droppedEntryCount = 0
  }

  private func waitForPersistsToIdle() async {
    guard persistsInFlight > 0 else { return }
    await withCheckedContinuation { continuation in
      persistIdleWaiters.append(continuation)
    }
  }

  private func beginPersist() {
    persistsInFlight += 1
  }

  private func endPersist() {
    persistsInFlight -= 1
    guard persistsInFlight == 0 else { return }
    let waiters = persistIdleWaiters
    persistIdleWaiters.removeAll()
    for waiter in waiters {
      waiter.resume()
    }
  }

  private func scheduleFlush() {
    guard !isFlushScheduled else { return }
    isFlushScheduled = true

    flushTask = Task {
      try? await Task.sleep(for: flushInterval)
      guard !Task.isCancelled else { return }
      isFlushScheduled = false
      await flushBuffer()
    }
  }

  private func flushNow() {
    flushTask?.cancel()
    flushTask = nil
    isFlushScheduled = false
    Task { await flushBuffer() }
  }

  private func flushBuffer() async {
    guard !buffer.isEmpty else { return }
    guard Self.isCollectionEnabled else {
      buffer = []
      return
    }
    beginPersist()
    defer { endPersist() }

    let entries = buffer
    buffer = []

    do {
      try await dataStore.saveDebugLogEntries(entries)
      guard Self.isCollectionEnabled else { return }
      await persistDropSummaryIfNeeded()
      await pruneIfDue()
    } catch {
      Self.logger.error("Failed to save debug logs: \(error.localizedDescription)")
      guard Self.isCollectionEnabled else { return }

      // Backpressure: only re-queue if total won't exceed limit
      let entriesToRequeue = Array(entries.prefix(Self.maxBufferSize))
      if buffer.count + entriesToRequeue.count < Self.maxBufferSize * 2 {
        buffer.insert(contentsOf: entriesToRequeue, at: 0)
        droppedEntryCount += entries.count - entriesToRequeue.count
      } else {
        droppedEntryCount += entries.count
      }
    }
  }

  func setLastPruneForTesting(_ date: Date) {
    lastPrune = date
  }

  private func pruneIfDue() async {
    guard Date().timeIntervalSince(lastPrune) >= DebugLogRetention.pruneInterval else { return }
    do {
      try await dataStore.pruneDebugLogEntries(
        olderThan: Date().addingTimeInterval(-DebugLogRetention.window),
        keepCount: DebugLogRetention.maxEntries
      )
      lastPrune = Date()
    } catch {
      Self.logger.error("Failed to prune debug logs: \(error.localizedDescription)")
    }
  }

  /// Reports drops since the last successful save, and leaves the count on failure so the loss carries forward.
  /// Snapshots the count before the save. The actor is reentrant across that await, so only the reported amount is subtracted.
  private func persistDropSummaryIfNeeded() async {
    guard droppedEntryCount > 0, !isPersistingDropSummary else { return }
    isPersistingDropSummary = true
    defer { isPersistingDropSummary = false }

    let reported = droppedEntryCount
    let summary = DebugLogEntryDTO(
      level: .warning,
      subsystem: Self.logSubsystem,
      category: Self.logCategory,
      message: "Lost \(reported) log entries due to prior save failures"
    )
    do {
      try await dataStore.saveDebugLogEntries([summary])
      droppedEntryCount -= reported
    } catch {
      Self.logger.error("Failed to persist dropped-entry summary: \(error.localizedDescription)")
    }
  }
}

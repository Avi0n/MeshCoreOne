import Foundation
import os

/// Actor for buffering debug log entries and flushing to persistence.
/// Provides batched saves for performance and backpressure handling.
public actor DebugLogBuffer {
  /// The current shared buffer and the entries recorded while none exists, behind
  /// one lock so `record` and the `shared` setter are atomic with respect to each
  /// other: an entry either reaches the current buffer or waits in `pending` for
  /// the next assignment to drain; it can't fall between the two.
  private struct SharedState {
    var buffer: DebugLogBuffer?
    var pending: [DebugLogEntryDTO] = []
  }

  private static let sharedState = OSAllocatedUnfairLock<SharedState>(initialState: SharedState())
  /// Depth of `disableCollection` calls in progress. The key write that turns
  /// collection off and a turn-on both happen under this lock, so a toggle
  /// during the wipe cannot land true.
  private struct CollectionDisable {
    var depth = 0
  }

  private static let collectionDisable = OSAllocatedUnfairLock(initialState: CollectionDisable())
  static let maxPendingEntries = 500

  /// Opt-in gate for persisted debug-log collection. Read fresh on every `record`
  /// so it works off the main actor and at early launch, before the first
  /// connection creates a buffer. A missing key means off (opt-in), matching both
  /// fresh installs and upgrades from any prior version. The app target writes it
  /// via `@AppStorage`; MC1Services only reads it, so this shares the app's
  /// standard `UserDefaults` domain.
  nonisolated static var isCollectionEnabled: Bool {
    UserDefaults.standard.bool(forKey: AppStorageKey.collectDebugLogs.rawValue)
  }

  /// Shared buffer instance for app-wide logging. Reassigned on every connection
  /// from `ServiceContainer.init` while `PersistentLogger` records from arbitrary
  /// actors. Assigning a buffer atomically takes every entry recorded while none
  /// existed (early launch, CoreBluetooth state restoration, background relaunch,
  /// between connections) and delivers them to the new buffer in record order.
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

  /// Single entry point for app-wide log delivery: hands `entry` to the current
  /// shared buffer, or queues it (bounded, oldest dropped first) until one is
  /// assigned. When collection is off, drops the entry before any queueing, buffer
  /// handoff, or `Task` creation. Entries already in `pending` or `buffer` are not
  /// discarded by a mere read of the setting; only `disableCollection` discards them.
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

  /// Turns collection on unless `disableCollection` owns it. A turn-on that
  /// loses the race with a disable in progress leaves the key false.
  public static func enableCollection() {
    let allowed = collectionDisable.withLock { $0.depth == 0 }
    guard allowed else { return }
    UserDefaults.standard.set(true, forKey: AppStorageKey.collectDebugLogs.rawValue)
    collectionDisable.withLock { state in
      guard state.depth > 0 else { return }
      UserDefaults.standard.set(false, forKey: AppStorageKey.collectDebugLogs.rawValue)
    }
  }

  /// Owns collection from the write that sets the key false until this returns.
  /// Waits out a save already inside `saveDebugLogEntries`, drops whatever that
  /// save put back in memory, then deletes rows. The save cannot finish after
  /// the delete. A throw leaves the key false and the rows that could not be
  /// deleted; the caller turns the key back on. The depth returns to zero
  /// before this returns, including on throw.
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

  /// Empties the pending queue so tests can exercise the no-buffer window from a
  /// known state.
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

  /// Entries lost since the last successful save, to a save failure or the requeue
  /// cap. Reported as a synthesized log entry on the next successful save, since
  /// those windows are otherwise invisible in the persisted log itself.
  private var droppedEntryCount = 0

  /// Guards `persistDropSummaryIfNeeded` against reentrancy: `flushBuffer` runs
  /// concurrently with itself via `flushNow`'s unstructured task, and two flushes
  /// succeeding together must not persist duplicate summaries.
  private var isPersistingDropSummary = false

  /// `flushBuffer` calls that have passed the collection gate and not yet
  /// returned. `disableCollection` waits for this to hit zero before it
  /// deletes rows, so a save already inside `saveDebugLogEntries` cannot
  /// finish after that delete.
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

  /// Waits out saves already in flight, then drops memory. Called only after
  /// the collection key is false, so a flush that starts during the wait
  /// does not publish.
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

  /// Persists a summary of entries dropped since the last successful save. Left
  /// uncounted on failure so the loss carries forward to the next attempt instead
  /// of being silently reset. Snapshots the count before the save because the
  /// actor is reentrant across that await: drops recorded while the save is
  /// suspended must survive, so only the reported amount is subtracted on success.
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

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
  static let maxPendingEntries = 500

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
  /// assigned.
  static func record(_ entry: DebugLogEntryDTO) {
    let buffer = sharedState.withLock { state -> DebugLogBuffer? in
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

  /// Entries lost since the last successful save, to a save failure or the requeue
  /// cap. Reported as a synthesized log entry on the next successful save, since
  /// those windows are otherwise invisible in the persisted log itself.
  private var droppedEntryCount = 0

  /// Guards `persistDropSummaryIfNeeded` against reentrancy: `flushBuffer` runs
  /// concurrently with itself via `flushNow`'s unstructured task, and two flushes
  /// succeeding together must not persist duplicate summaries.
  private var isPersistingDropSummary = false

  private static let logSubsystem = "com.mc1"
  private static let logCategory = "DebugLogBuffer"
  private static let logger = Logger(subsystem: logSubsystem, category: logCategory)

  public init(dataStore: any DebugLogPersisting) {
    self.dataStore = dataStore
  }

  public func append(_ entry: DebugLogEntryDTO) {
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
    let entries = buffer
    buffer = []

    do {
      try await dataStore.saveDebugLogEntries(entries)
      await persistDropSummaryIfNeeded()
    } catch {
      Self.logger.error("Failed to save debug logs: \(error.localizedDescription)")

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

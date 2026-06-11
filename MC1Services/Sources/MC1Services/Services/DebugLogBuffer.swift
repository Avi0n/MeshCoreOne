import Foundation
import os

/// Actor for buffering debug log entries and flushing to persistence.
/// Provides batched saves for performance and backpressure handling.
public actor DebugLogBuffer {
    /// Backing storage for the shared buffer. Reassigned on every connection from
    /// `ServiceContainer.init` while `PersistentLogger` reads it from arbitrary
    /// actors, so the write and the reads must be synchronized.
    private static let sharedStorage = OSAllocatedUnfairLock<DebugLogBuffer?>(initialState: nil)

    /// Shared buffer instance for app-wide logging.
    public static var shared: DebugLogBuffer? {
        get { sharedStorage.withLock { $0 } }
        set { sharedStorage.withLock { $0 = newValue } }
    }

    private let dataStore: any PersistenceStoreProtocol
    private var buffer: [DebugLogEntryDTO] = []
    private var flushTask: Task<Void, Never>?
    private var isFlushScheduled = false
    private let flushInterval: Duration = .seconds(5)
    private let maxBufferSize = 50

    private static let logger = Logger(subsystem: "com.mc1", category: "DebugLogBuffer")

    public init(dataStore: any PersistenceStoreProtocol) {
        self.dataStore = dataStore
    }

    public func append(_ entry: DebugLogEntryDTO) {
        buffer.append(entry)

        if buffer.count >= maxBufferSize {
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
        } catch {
            Self.logger.error("Failed to save debug logs: \(error.localizedDescription)")

            // Backpressure: only re-queue if total won't exceed limit
            let entriesToRequeue = Array(entries.prefix(maxBufferSize))
            if buffer.count + entriesToRequeue.count < maxBufferSize * 2 {
                buffer.insert(contentsOf: entriesToRequeue, at: 0)
            }
        }
    }
}

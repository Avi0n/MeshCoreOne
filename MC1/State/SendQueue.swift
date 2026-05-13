import Foundation

/// Serial drain of `Sendable` envelopes. The actor owns the pending list and
/// a single drain `Task`; multiple `enqueue` calls during an active drain
/// append to the same drain pass. Cancellation requeues at the front, and the
/// cancelled task's natural completion respawns the drain if pending is
/// non-empty — so cancel-then-enqueue cannot strand work.
///
/// Errors thrown by `send`:
/// - `CancellationError` re-inserts the envelope at index 0 and returns.
///   The cancelled task's completion path then respawns the drain.
/// - Any other error fires `onError(error, envelope)` and the drain
///   continues with the next envelope. Per-envelope failures never stop
///   the queue.
///
/// After the pending list empties, `onDrain(_:)` fires exactly once per
/// drain completion with the most recent non-cancellation error (or nil).
///
/// `cancel()` cancels the in-flight drain task but does not spawn a
/// replacement. The cancelled task re-inserts its in-flight envelope and
/// returns; `taskCompleted(generation:)` then respawns if pending is
/// non-empty. Serialising cancel/respawn this way is what preserves FIFO
/// across cancel-then-enqueue races.
///
/// The drain `Task` captures the actor strongly so the actor lives until
/// the drain completes — a popped owner mid-drain cannot strand an
/// in-flight send. Strong capture is broken naturally by Task completion.
actor SendQueue<Envelope: Sendable> {

    typealias Sender  = @Sendable (Envelope) async throws -> Void
    typealias OnError = @Sendable (Error, Envelope) async -> Void

    /// Fires once per drain pass after the inner `while !pending.isEmpty`
    /// completes. The parameter is the most recent non-cancellation
    /// `Error` raised by `send(_:)` during this drain (or `nil` if no
    /// envelope failed). Last-error-wins matches the original
    /// `processQueue`'s `var pendingError: String?` semantics.
    typealias OnDrain = @Sendable (Error?) async -> Void

    private var pending: [Envelope] = []
    private var processingTask: Task<Void, Never>?
    private var processingTaskGeneration: Int = 0
    private let send: Sender
    private let onError: OnError
    private let onDrain: OnDrain

    init(
        send: @escaping Sender,
        onError: @escaping OnError,
        onDrain: @escaping OnDrain
    ) {
        self.send = send
        self.onError = onError
        self.onDrain = onDrain
    }

    /// Append an envelope and ensure a drain task is running.
    func enqueue(_ envelope: Envelope) {
        pending.append(envelope)
        ensureDraining()
    }

    /// Number of envelopes waiting. Exposed for tests; no view consumer.
    var count: Int { pending.count }

    /// Cancel the in-flight drain. Pending envelopes are not cleared. The
    /// cancelled task's `catch is CancellationError` branch re-inserts its
    /// in-flight envelope at index 0, and `taskCompleted(generation:)`
    /// then respawns the drain if pending is non-empty. A subsequent
    /// `enqueue` while cancellation is mid-flight is honored by that
    /// respawn, so pending envelopes never lose their drain.
    func cancel() {
        processingTask?.cancel()
    }

    /// Await the current drain pass to completion. If no drain is in
    /// progress this returns immediately. Used by tests to synchronize
    /// on the drain task without polling — production consumers observe
    /// drain completion via the `onDrain` callback.
    func awaitDrainCompletion() async {
        await processingTask?.value
    }

    private func ensureDraining() {
        // Only one task in flight at a time. A cancelled task that hasn't
        // yet completed is still "in flight" for FIFO purposes — let it
        // requeue first, then taskCompleted respawns.
        guard processingTask == nil else { return }
        spawnDrainTask()
    }

    private func spawnDrainTask() {
        processingTaskGeneration += 1
        let myGeneration = processingTaskGeneration
        processingTask = Task { [self] in
            await drain()
            taskCompleted(generation: myGeneration)
        }
    }

    private func taskCompleted(generation: Int) {
        // Defense-in-depth: bail if a newer task has already taken the slot.
        // With the serialized cancel/respawn above this should not happen.
        guard generation == processingTaskGeneration else { return }
        processingTask = nil
        // Cancellation may have re-inserted an envelope before returning;
        // respawn so that envelope drains.
        if !pending.isEmpty {
            spawnDrainTask()
        }
    }

    private func drain() async {
        var lastError: Error?
        repeat {
            while !pending.isEmpty {
                let envelope = pending.removeFirst()
                do {
                    try await send(envelope)
                } catch is CancellationError {
                    pending.insert(envelope, at: 0)
                    return
                } catch {
                    lastError = error
                    await onError(error, envelope)
                }
            }
            // Outer re-check after onDrain handler suspends. The handler
            // typically calls loadMessages/loadConversations, during which an
            // enqueue can land. Without this outer pass, that envelope would
            // sit in pending with no scheduled drain until a future enqueue.
            await onDrain(lastError)
        } while !pending.isEmpty
    }
}

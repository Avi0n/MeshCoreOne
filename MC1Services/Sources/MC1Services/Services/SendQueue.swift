import Foundation

/// Serial drain of `Sendable` envelopes. The actor owns the pending list and
/// a single drain `Task`; multiple `enqueue` calls during an active drain
/// append to the same drain pass.
///
/// Errors thrown by `send`:
/// - `CancellationError` re-inserts the envelope at index 0 and returns.
///   `taskCompleted` then respawns the drain so the requeued envelope
///   retries. This covers send closures that honour cancellation internally
///   (e.g., `try Task.checkCancellation()` inside a service call).
/// - Any other error fires `onError(error, envelope)` and the drain
///   continues with the next envelope. Per-envelope failures never stop
///   the queue.
///
/// After the pending list empties, `onDrain(_:)` fires exactly once per
/// drain completion with the most recent non-cancellation error (or nil).
/// An enqueue landing during the `onDrain` await is picked up by the outer
/// `repeat-while`, so a follow-up envelope cannot sit unscheduled.
///
/// The drain `Task` captures the actor strongly so the actor lives until
/// the drain completes — a popped owner mid-drain cannot strand an
/// in-flight send. Strong capture is broken naturally by Task completion.
public actor SendQueue<Envelope: Sendable> {

    public typealias Sender  = @Sendable (Envelope) async throws -> Void
    public typealias OnError = @Sendable (Error, Envelope) async -> Void

    /// Fires once per drain pass after the inner `while !pending.isEmpty`
    /// completes. The parameter is the most recent non-cancellation
    /// `Error` raised by `send(_:)` during this drain (or `nil` if no
    /// envelope failed). Last-error-wins matches the original
    /// `processQueue`'s `var pendingError: String?` semantics.
    public typealias OnDrain = @Sendable (Error?) async -> Void

    private var pending: [Envelope] = []
    private var processingTask: Task<Void, Never>?
    private let send: Sender
    private let onError: OnError
    private let onDrain: OnDrain

    public init(
        send: @escaping Sender,
        onError: @escaping OnError,
        onDrain: @escaping OnDrain
    ) {
        self.send = send
        self.onError = onError
        self.onDrain = onDrain
    }

    /// Append an envelope and ensure a drain task is running.
    public func enqueue(_ envelope: Envelope) {
        pending.append(envelope)
        ensureDraining()
    }

    #if DEBUG
    /// Number of envelopes waiting. Exposed for tests; no view consumer.
    public var count: Int { pending.count }

    /// Await the current drain pass to completion. If no drain is in
    /// progress this returns immediately. Used by tests to synchronize
    /// on the drain task without polling — production consumers observe
    /// drain completion via the `onDrain` callback.
    public func awaitDrainCompletion() async {
        await processingTask?.value
    }
    #endif

    /// Cancel the in-flight drain task and suppress the auto-respawn that
    /// would otherwise follow a `CancellationError` re-insertion. The
    /// current send closure's next `await` propagates `CancellationError`,
    /// which the `catch is CancellationError` branch handles by
    /// re-inserting the envelope; `taskCompleted` then observes
    /// `Task.isCancelled` and skips its respawn so the queue goes
    /// dormant. A subsequent `enqueue(_:)` schedules a fresh drain task
    /// because `processingTask` is back to `nil`.
    ///
    /// Provided so test teardown can release a SendQueue whose send
    /// closure suspends, and so a future production teardown does not
    /// leak the actor through an unbounded respawn cycle.
    public func cancelDrain() {
        processingTask?.cancel()
    }

    private func ensureDraining() {
        // Only one task in flight at a time. A draining task that requeued
        // via CancellationError but hasn't yet completed still holds the
        // slot; taskCompleted will respawn after it returns.
        guard processingTask == nil else { return }
        spawnDrainTask()
    }

    private func spawnDrainTask() {
        processingTask = Task { [self] in
            await drain()
            taskCompleted()
        }
    }

    private func taskCompleted() {
        processingTask = nil
        // If the completing task was cancelled (via cancelDrain or an
        // upstream cancellation), treat it as an intentional halt — leave
        // the queue dormant. A later enqueue still schedules a fresh
        // drain task because processingTask is back to nil.
        if Task.isCancelled { return }
        // A send-closure CancellationError may have re-inserted an envelope
        // before this task returned; respawn so that envelope drains.
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

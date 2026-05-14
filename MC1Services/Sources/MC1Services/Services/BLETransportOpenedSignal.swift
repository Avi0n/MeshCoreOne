import Foundation

/// Single-purpose wake signal: a job suspended in
/// `withCooperativeTimeout` waits for the BLE transport to reopen.
/// `wait()` throws `CancellationError` when the calling task is
/// cancelled before `fire()` lands; callers handle the throw with the
/// same logic as a timeout (park the envelope, requeue).
public actor BLETransportOpenedSignal {

    private struct Waiter {
        let id: UInt64
        let continuation: CheckedContinuation<Void, Error>
    }

    private var armed: Bool = false
    private var waiters: [Waiter] = []
    private var nextWaiterID: UInt64 = 0

    public init() {}

    /// Suspend until `fire()` lands. If the signal is already armed at
    /// call time, the call returns immediately and consumes the armed
    /// flag. Throws `CancellationError` if the calling task is cancelled
    /// before the signal fires.
    public func wait() async throws {
        if armed {
            armed = false
            return
        }
        let waiterID = nextWaiterID
        nextWaiterID &+= 1
        try await withTaskCancellationHandler {
            let _: Void = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                if Task.isCancelled {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                waiters.append(Waiter(id: waiterID, continuation: continuation))
            }
        } onCancel: {
            Task { [weak self] in
                await self?.handleCancellation(id: waiterID)
            }
        }
    }

    /// Mark the signal as fired. Wakes every waiter; arms the flag for
    /// the next `wait()` call if no waiters are currently suspended.
    public func fire() {
        var anyResumed = false
        for waiter in waiters {
            waiter.continuation.resume()
            anyResumed = true
        }
        waiters.removeAll()
        if !anyResumed {
            armed = true
        }
    }

    /// Drop any armed-pending state. Call sites: only after a successful
    /// send, not before each attempt. The consume-on-wait semantic in
    /// `wait()` already handles "fire landed during the previous attempt"
    /// cleanly.
    public func clear() {
        armed = false
    }

    private func handleCancellation(id: UInt64) {
        guard let index = waiters.firstIndex(where: { $0.id == id }) else { return }
        let waiter = waiters.remove(at: index)
        waiter.continuation.resume(throwing: CancellationError())
    }
}

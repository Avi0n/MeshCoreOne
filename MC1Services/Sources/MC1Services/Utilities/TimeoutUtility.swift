import Foundation

// MARK: - Timeout Error

/// Error thrown when an async operation exceeds its timeout.
public struct TimeoutError: Error, LocalizedError, Sendable {
    public let operationName: String
    public let timeout: Duration

    public var errorDescription: String? {
        "Operation '\(operationName)' timed out after \(timeout)"
    }
}

// MARK: - Timeout Helpers

/// Races an async operation against a deadline, throwing `TimeoutError` when the
/// deadline fires first. Uses `SuspendingClock` so the deadline pauses while iOS
/// suspends the app; current callers wrap BLE operations that should not time out
/// during suspension. See `raceAgainstDeadline` for the cancellation contract.
public func withTimeout<T: Sendable>(
    _ timeout: Duration,
    operationName: String = #function,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await raceAgainstDeadline(
        sleepUntilDeadline: { try await Task.sleep(for: timeout, clock: .suspending) },
        makeTimeoutError: { TimeoutError(operationName: operationName, timeout: timeout) },
        operation: operation
    )
}

/// Races an async operation against a deadline, throwing `CancellationError` when
/// the deadline fires first. `SendQueue.drain`'s park-and-requeue protocol depends
/// on that error type; use `withTimeout` when the caller needs a distinguishable
/// timeout error. Uses the default `ContinuousClock`, so the deadline keeps
/// elapsing while the app is suspended. See `raceAgainstDeadline` for the
/// cancellation contract.
public func withCooperativeTimeout<T: Sendable>(
    seconds: TimeInterval,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await raceAgainstDeadline(
        sleepUntilDeadline: { try await Task.sleep(for: .seconds(seconds)) },
        makeTimeoutError: { CancellationError() },
        operation: operation
    )
}

/// Core racer shared by `withTimeout` and `withCooperativeTimeout`.
///
/// The timeout is cooperative and therefore advisory: `cancelAll()` only requests
/// cancellation, and the task group must still await the operation child before
/// returning. An operation parked on a cancellation-ignoring continuation keeps
/// this function suspended past the deadline; the timeout cannot unblock it.
///
/// `defer { group.cancelAll() }` guarantees the losing child is cancelled on every
/// exit path (success, operation throw, deadline). Without it, a deadline-driven
/// throw leaves `operation` running until it completes naturally, leaking any
/// in-flight continuation it owned (e.g. `BLETransportOpenedSignal.wait`'s pending
/// waiter slot).
private func raceAgainstDeadline<T: Sendable>(
    sleepUntilDeadline: @escaping @Sendable () async throws -> Void,
    makeTimeoutError: @escaping @Sendable () -> any Error,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        defer { group.cancelAll() }
        group.addTask { try await operation() }
        group.addTask {
            try await sleepUntilDeadline()
            throw makeTimeoutError()
        }
        guard let result = try await group.next() else {
            throw makeTimeoutError()
        }
        return result
    }
}

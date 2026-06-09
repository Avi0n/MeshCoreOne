import Foundation

/// Runs `operation` with a hard timeout. If `operation` does not return
/// before `seconds` elapse, the wrapping task is cancelled and a
/// `CancellationError` is thrown. Cooperative — `operation` must
/// respect cancellation.
///
/// `defer { group.cancelAll() }` guarantees the sibling task is
/// cancelled on every exit path (success, throw, deadline). Without
/// the defer, a deadline-driven throw leaves `operation` running until
/// it completes naturally, leaking any in-flight continuation it owned
/// (e.g. `BLETransportOpenedSignal.wait`'s pending waiter slot).
public func withCooperativeTimeout<T: Sendable>(
    seconds: TimeInterval,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        defer { group.cancelAll() }
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(for: .seconds(seconds))
            throw CancellationError()
        }
        guard let value = try await group.next() else {
            throw CancellationError()
        }
        return value
    }
}

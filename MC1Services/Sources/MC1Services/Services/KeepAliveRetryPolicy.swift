import MeshCore

/// Encapsulates keep-alive retry decisions for testability.
///
/// Classifies errors into five categories:
/// - **Transient** (timeout, deviceError, notConnected): retried up to `maxConsecutiveFailures` times
/// - **Terminal** (sessionNotFound, contactNotFound, unknown errors): disconnect immediately
/// - **Skip** (floodRouted): not a failure, continue the loop
/// - **Stop** (CancellationError, cancelled): task was cancelled, exit quietly
enum KeepAliveRetryPolicy {
    enum Action: Equatable {
        /// Transient failure, try again next interval
        case retryNextInterval
        /// Consecutive transient failures exceeded threshold
        case disconnect
        /// Terminal local-state error, disconnect immediately
        case disconnectNow
        /// Flood-routed session, skip this iteration
        case skip
        /// Task cancelled, exit loop quietly
        case stop

        var shouldExitLoop: Bool {
            switch self {
            case .stop, .disconnect, .disconnectNow: true
            case .retryNextInterval, .skip: false
            }
        }
    }

    /// The number of consecutive transient failures required before disconnecting.
    static let maxConsecutiveFailures = 2

    /// Evaluates a keep-alive error and returns the appropriate action.
    static func evaluate(
        error: Error,
        consecutiveFailures: inout Int
    ) -> Action {
        if error is CancellationError {
            return .stop
        }

        guard let nodeError = error as? RemoteNodeError else {
            return .disconnectNow
        }

        switch nodeError {
        case .cancelled:
            return .stop
        case .floodRouted:
            return .skip
        case .sessionNotFound, .contactNotFound:
            return .disconnectNow
        default:
            consecutiveFailures += 1
            return consecutiveFailures >= maxConsecutiveFailures ? .disconnect : .retryNextInterval
        }
    }

    /// Records a successful keep-alive by resetting the failure counter.
    static func recordSuccess(consecutiveFailures: inout Int) {
        consecutiveFailures = 0
    }

    /// Returns a human-readable reason for a keep-alive failure.
    static func failureReason(for error: Error) -> String {
        switch error {
        case RemoteNodeError.sessionError(.timeout):
            return "timeout"
        case RemoteNodeError.sessionError(.deviceError(let code)):
            return "device error (code: \(code))"
        case RemoteNodeError.sessionError(.notConnected):
            return "transport not connected"
        case RemoteNodeError.sessionNotFound:
            return "session not found"
        case RemoteNodeError.contactNotFound:
            return "contact not found"
        default:
            return "\(error)"
        }
    }
}

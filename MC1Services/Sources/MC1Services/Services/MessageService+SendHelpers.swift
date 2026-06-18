import Foundation
import MeshCore

private let millisecondsPerSecond: Int = 1000

private func poolBackoffDelay(forInLoopAttempt attempt: Int, config: PoolBackoffConfig) -> Duration {
    let base = config.baseDelay * pow(config.exponentBase, Double(attempt))
    let jittered = base * Double.random(in: config.jitterRange)
    return .milliseconds(Int(jittered * Double(millisecondsPerSecond)))
}

/// In-loop absorption of short pool-exhaustion bursts. The firmware briefly
/// returns `deviceError(transientCode)` when its outbound pool is full; this
/// helper sleeps according to `config` between attempts (default 500ms / 1s /
/// 2s with +/- 20% jitter) and re-throws once `config.attemptCap` is reached.
func withPoolBackoff<T>(
    transientCode: UInt8,
    config: PoolBackoffConfig,
    logger: PersistentLogger,
    operation: sending () async throws -> T
) async throws -> sending T {
    var attempt = 0
    while true {
        do {
            return try await operation()
        } catch let error as MeshCoreError {
            guard case .deviceError(let code) = error, code == transientCode else {
                throw error
            }
            guard attempt < config.attemptCap else {
                logger.warning("Pool backoff exhausted after \(attempt) attempts; re-throwing transient deviceError(\(code))")
                throw error
            }
            try await Task.sleep(for: poolBackoffDelay(forInLoopAttempt: attempt, config: config))
            attempt += 1
        }
    }
}

extension MessageService {

    func failMessageAndRethrow(_ error: Error, messageID: UUID) async throws -> Never {
        pendingAcks.removeValue(forKey: messageID)
        // Persistence layer absorbs `.delivered`: if the listener won the race
        // before the throw path runs, the row stays delivered. Same invariant as
        // updateMessageRetryStatus and updateMessageAck. The Bool return is
        // intentionally discarded; the caller observes the failure via the
        // rethrown error. The caller is responsible for broadcasting the
        // `.failed` event: inline non-queue catch sites yield it one line
        // above this call; queue-routed catch sites delegate to the queue's
        // outer catch, which calls `notifyMessageFailed` exactly once on any
        // non-`CancellationError` escape.
        _ = try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
        if let meshError = error as? MeshCoreError {
            throw MessageServiceError.sessionError(meshError)
        }
        throw error
    }
}

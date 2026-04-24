import Foundation

/// How long an ACK can still arrive after its timeout before the message is
/// marked `.failed`.
private let lateAckGraceWindow: TimeInterval = 5

// MARK: - Periodic ACK Checking

extension MessageService {

    /// Starts periodic checking for expired ACKs.
    ///
    /// Runs a background task that periodically marks messages as `.failed`
    /// after their ACK timeout plus the late-ACK grace window has elapsed.
    ///
    /// - Parameter interval: How often to check for expired ACKs (defaults to 5 seconds)
    ///
    /// # Lifecycle scope
    ///
    /// Independent from `startEventMonitoring()`. Counterparts are
    /// `stopAckExpiryChecking()` (stop the checker only) and
    /// `stopAndFailAllPending()` (stop the checker and fail every in-flight
    /// DM — the disconnect-teardown variant). `stopEventMonitoring()` does
    /// **not** stop this task.
    public func startAckExpiryChecking(interval: TimeInterval = 5.0) {
        self.checkInterval = interval
        ackCheckTask?.cancel()

        ackCheckTask = Task { [weak self] in
            guard let self else { return }

            while !Task.isCancelled {
                do {
                    try await Task.sleep(for: .seconds(self.checkInterval))
                } catch {
                    break
                }

                guard !Task.isCancelled else { break }

                do {
                    try await self.checkExpiredAcks()
                } catch {
                    self.logger.error("ACK expiry check failed: \(error.localizedDescription)")
                }
            }
        }
    }

    /// Stops the periodic ACK expiry checking.
    ///
    /// Cancels `ackCheckTask` only. Does not stop the session event listener
    /// (`stopEventMonitoring()`) and does not fail in-flight DMs
    /// (`stopAndFailAllPending()` does both).
    public func stopAckExpiryChecking() {
        ackCheckTask?.cancel()
        ackCheckTask = nil
    }

    /// Checks for expired ACKs and advances their delivery state.
    ///
    /// Called automatically by the periodic checker, or manually for an
    /// immediate check. Messages stay `.sent` through the late-ACK grace
    /// window: the `pendingAcks` entry remains so an ACK arriving late still
    /// reconciles via `handleAcknowledgement`'s direct lookup. Only after the
    /// grace window elapses does the message move to `.failed`.
    ///
    /// - Throws: Database errors when updating message status
    public func checkExpiredAcks() async throws {
        let now = Date()

        let expiredEntries = pendingAcks.filter { _, tracking in
            !tracking.isDelivered &&
            now.timeIntervalSince(tracking.sentAt) > tracking.timeout + lateAckGraceWindow
        }

        for (messageID, _) in expiredEntries {
            let didFail = try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
            guard let removed = pendingAcks.removeValue(forKey: messageID),
                  !removed.isDelivered, didFail else { continue }

            logger.warning("Message failed - timeout exceeded")
            await messageFailedHandler?(messageID)
        }
    }

    /// Fails all pending messages that are awaiting ACK.
    ///
    /// Use this when disconnecting from the device to mark all in-flight messages as failed.
    ///
    /// - Throws: Database errors when updating message status
    public func failAllPendingMessages() async throws {
        let pending = pendingAcks.filter { !$0.value.isDelivered }
        pendingAcks.removeAll()

        for (messageID, _) in pending {
            let didFail = try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
            if didFail {
                await messageFailedHandler?(messageID)
            }
        }
    }

    /// Stops ACK checking and fails all pending messages atomically.
    ///
    /// This is the recommended method to call when disconnecting from a device.
    /// It ensures the periodic checker is stopped and all pending messages are marked as failed.
    ///
    /// - Throws: Database errors when updating message status
    public func stopAndFailAllPending() async throws {
        ackCheckTask?.cancel()
        ackCheckTask = nil

        try await failAllPendingMessages()
    }

    /// The current number of pending ACKs being tracked.
    ///
    /// Includes undelivered messages still inside the late-ACK grace window.
    public var pendingAckCount: Int {
        pendingAcks.count
    }

    /// Whether ACK expiry checking is currently active.
    public var isAckExpiryCheckingActive: Bool {
        ackCheckTask != nil
    }
}

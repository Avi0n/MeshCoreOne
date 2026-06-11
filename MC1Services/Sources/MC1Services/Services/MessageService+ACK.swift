import Foundation

// MARK: - Periodic ACK Checking

extension MessageService {

    /// Starts periodic checking for expired ACKs.
    ///
    /// Runs a background task that periodically marks messages as `.failed`
    /// once `config.ackGiveUpWindow` has elapsed since the message was last sent.
    ///
    /// - Parameter interval: How often to check for expired ACKs (defaults to 5 seconds)
    ///
    /// # Lifecycle scope
    ///
    /// Independent from `startEventMonitoring()`. Counterparts are
    /// `stopAckExpiryChecking()` (stop the checker only) and
    /// `stopAndFailAllPending()` (stop the checker and fail every in-flight
    /// DM — the explicit full-teardown variant). `stopEventMonitoring()` does
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
    /// (`stopAndFailAllPending()` does both). This is the stop variant used on
    /// a routine disconnect: in-flight DMs stay `.sent` so a reconnect within
    /// the same session can still receive the ACK.
    public func stopAckExpiryChecking() {
        ackCheckTask?.cancel()
        ackCheckTask = nil
    }

    /// Checks for expired ACKs and advances their delivery state.
    ///
    /// Called automatically by the periodic checker, or manually for an
    /// immediate check. A sent DM stays `.sent` until `config.ackGiveUpWindow`
    /// elapses since its last send attempt: the `pendingAcks` entry remains so
    /// an ACK arriving within that window still reconciles via
    /// `handleAcknowledgement`'s direct lookup. Only after the window elapses
    /// does the message move to `.failed`, and this is the single place a DM
    /// awaiting an ACK is failed.
    ///
    /// - Throws: Database errors when updating message status
    public func checkExpiredAcks() async throws {
        let now = Date()
        let window = config.ackGiveUpWindow

        let expiredEntries = pendingAcks.filter { _, tracking in
            !tracking.isDelivered &&
            now.timeIntervalSince(tracking.sentAt) > window
        }

        for (messageID, _) in expiredEntries {
            let didFail = try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
            guard let removed = pendingAcks.removeValue(forKey: messageID),
                  !removed.isDelivered, didFail else { continue }

            logger.warning("[ack-diag] give-up: failed after \(String(format: "%.1f", now.timeIntervalSince(removed.sentAt)))s window=\(window)s livePending=\(pendingAcks.count)")
            statusEventBroadcaster.yield(.failed(messageID: messageID))
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
                statusEventBroadcaster.yield(.failed(messageID: messageID))
            }
        }
    }

    /// Stops ACK checking and fails all pending messages atomically.
    ///
    /// Use this only for an explicit full teardown where in-flight DMs should
    /// be terminated. A routine disconnect uses `stopAckExpiryChecking()`
    /// instead, which leaves in-flight DMs `.sent` so a reconnect can still
    /// receive their ACKs.
    ///
    /// - Throws: Database errors when updating message status
    public func stopAndFailAllPending() async throws {
        ackCheckTask?.cancel()
        ackCheckTask = nil

        try await failAllPendingMessages()
    }

    /// The current number of pending ACKs being tracked.
    ///
    /// Includes undelivered messages still inside the `ackGiveUpWindow`.
    public var pendingAckCount: Int {
        pendingAcks.count
    }

    /// Whether ACK expiry checking is currently active.
    public var isAckExpiryCheckingActive: Bool {
        ackCheckTask != nil
    }
}

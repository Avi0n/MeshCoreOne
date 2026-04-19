import Foundation

// MARK: - Periodic ACK Checking

extension MessageService {

    /// Starts periodic checking for expired ACKs.
    ///
    /// This method runs a background task that periodically checks for messages
    /// that have exceeded their ACK timeout and marks them as failed.
    ///
    /// - Parameter interval: How often to check for expired ACKs (defaults to 5 seconds)
    ///
    /// # Important
    /// This should be started when the connection is established and stopped when disconnecting.
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

                try? await self.checkExpiredAcks()
            }
        }
    }

    /// Stops the periodic ACK expiry checking.
    ///
    /// Call this when disconnecting from the device.
    public func stopAckExpiryChecking() {
        ackCheckTask?.cancel()
        ackCheckTask = nil
    }

    /// Checks for expired ACKs and marks their messages as failed.
    ///
    /// This is called automatically by the periodic checker. You can also call it
    /// manually to force an immediate check.
    ///
    /// - Throws: Database errors when updating message status
    public func checkExpiredAcks() async throws {
        let now = Date()

        let expiredIDs = pendingAcks.filter { _, tracking in
            !tracking.isDelivered &&
            now.timeIntervalSince(tracking.sentAt) > tracking.timeout
        }.keys

        for messageID in expiredIDs {
            guard pendingAcks.removeValue(forKey: messageID) != nil else { continue }
            try await dataStore.updateMessageStatus(id: messageID, status: .failed)
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
        let pendingIDs = pendingAcks.filter { !$0.value.isDelivered }.keys
        pendingAcks.removeAll()

        for messageID in pendingIDs {
            try await dataStore.updateMessageStatus(id: messageID, status: .failed)
            await messageFailedHandler?(messageID)
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
    /// This includes both undelivered messages and recently delivered messages
    /// still in the grace period for tracking repeats.
    public var pendingAckCount: Int {
        pendingAcks.count
    }

    /// Whether ACK expiry checking is currently active.
    public var isAckExpiryCheckingActive: Bool {
        ackCheckTask != nil
    }
}

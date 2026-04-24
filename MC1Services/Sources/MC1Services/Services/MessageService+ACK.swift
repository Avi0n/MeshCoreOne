import Foundation

/// How long an ACK can still arrive after its timeout before the message is
/// marked `.failed`.
private let lateAckGraceWindow: TimeInterval = 5

/// Upper bound on in-memory retention of recently-failed ACK codes for the
/// late-ACK grace window.
private let recentlyFailedAcksMaxSize = 64

// MARK: - Periodic ACK Checking

extension MessageService {

    /// Starts periodic checking for expired ACKs.
    ///
    /// This method runs a background task that periodically checks for messages
    /// that have exceeded their ACK timeout, moves them through a short
    /// retrying grace window, then marks them failed if no ACK arrives.
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
    /// Call this when disconnecting from the device.
    public func stopAckExpiryChecking() {
        ackCheckTask?.cancel()
        ackCheckTask = nil
    }

    /// Checks for expired ACKs and advances their delivery state.
    ///
    /// This is called automatically by the periodic checker. You can also call it
    /// manually to force an immediate check. A message is only marked failed
    /// after both its ACK timeout and the late-ACK grace window have elapsed.
    ///
    /// - Throws: Database errors when updating message status
    public func checkExpiredAcks() async throws {
        let now = Date()

        let expiredEntries = pendingAcks.filter { _, tracking in
            !tracking.isDelivered &&
            now.timeIntervalSince(tracking.sentAt) > tracking.timeout
        }

        for (messageID, tracking) in expiredEntries {
            let elapsed = now.timeIntervalSince(tracking.sentAt)
            if elapsed <= tracking.timeout + lateAckGraceWindow {
                guard var current = pendingAcks[messageID],
                      !current.isDelivered,
                      !current.isInAckGracePeriod else { continue }

                current.isInAckGracePeriod = true
                pendingAcks[messageID] = current

                try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .retrying)
                guard pendingAcks[messageID]?.isInAckGracePeriod == true else { continue }
                await retryStatusHandler?(messageID, 0, 0)
                continue
            }

            try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
            guard let removed = pendingAcks.removeValue(forKey: messageID),
                  !removed.isDelivered else { continue }

            logger.warning("Message failed - timeout exceeded")
            await messageFailedHandler?(messageID)
        }
        pruneRecentlyFailedAcks(now: now)
    }

    private func pruneRecentlyFailedAcks(now: Date) {
        recentlyFailedAcks = recentlyFailedAcks.filter { _, entry in
            now.timeIntervalSince(entry.failedAt) <= lateAckGraceWindow
        }
        guard recentlyFailedAcks.count > recentlyFailedAcksMaxSize else { return }
        let keep = recentlyFailedAcks
            .sorted { $0.value.failedAt > $1.value.failedAt }
            .prefix(recentlyFailedAcksMaxSize)
        recentlyFailedAcks = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
    }

    /// Fails all pending messages that are awaiting ACK.
    ///
    /// Use this when disconnecting from the device to mark all in-flight messages as failed.
    ///
    /// - Throws: Database errors when updating message status
    public func failAllPendingMessages() async throws {
        let now = Date()
        let pending = pendingAcks.filter { !$0.value.isDelivered }
        pendingAcks.removeAll()

        for (messageID, tracking) in pending {
            try await dataStore.updateMessageStatus(id: messageID, status: .failed)
            for ackCode in tracking.ackCodes {
                recentlyFailedAcks[ackCode] = (messageID, now)
            }
            await messageFailedHandler?(messageID)
        }
        pruneRecentlyFailedAcks(now: now)
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
    /// This includes undelivered messages that are still inside their retrying
    /// grace window.
    public var pendingAckCount: Int {
        pendingAcks.count
    }

    /// Whether ACK expiry checking is currently active.
    public var isAckExpiryCheckingActive: Bool {
        ackCheckTask != nil
    }

    /// Reconciles a late-arriving ACK against the recently-failed ring.
    ///
    /// Called from `handleAcknowledgement` when no live `pendingAcks` entry
    /// matches. If the ACK code is still in the grace window, promotes the
    /// previously-failed message back to `.delivered`.
    func reconcileLateAck(code: Data, tripTime: UInt32?) async {
        let now = Date()
        guard let entry = recentlyFailedAcks.removeValue(forKey: code),
              now.timeIntervalSince(entry.failedAt) <= lateAckGraceWindow else {
            return
        }
        let ackCodeUInt32 = code.ackCodeUInt32
        do {
            try await dataStore.updateMessageAck(
                id: entry.messageID,
                ackCode: ackCodeUInt32,
                status: .delivered,
                roundTripTime: tripTime
            )
            notifyAckConfirmation(ackCode: ackCodeUInt32, tripTime: tripTime)
            logger.notice("late ACK reconciled: message promoted .failed → .delivered")
        } catch {
            logger.error("late ACK reconciliation failed: \(error.localizedDescription)")
        }
    }
}

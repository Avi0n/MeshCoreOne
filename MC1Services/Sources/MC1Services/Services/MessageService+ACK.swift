import Foundation

// MARK: - Periodic ACK Checking

public extension MessageService {
  /// Starts periodic checking for expired ACKs.
  ///
  /// Runs a background task that periodically fails a DM awaiting an ACK once
  /// its per-entry give-up deadline elapses; `checkExpiredAcks` defines that
  /// deadline.
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
  func startAckExpiryChecking(interval: TimeInterval = 5.0) {
    checkInterval = interval
    ackCheckTask?.cancel()

    ackCheckTask = Task { [weak self] in
      guard let self else { return }

      while !Task.isCancelled {
        do {
          try await Task.sleep(for: .seconds(checkInterval))
        } catch {
          break
        }

        guard !Task.isCancelled else { break }

        do {
          try await checkExpiredAcks()
        } catch {
          logger.error("ACK expiry check failed: \(error.localizedDescription)")
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
  func stopAckExpiryChecking() {
    ackCheckTask?.cancel()
    ackCheckTask = nil
  }

  /// Checks for expired ACKs and advances their delivery state.
  ///
  /// Called automatically by the periodic checker, or manually for an
  /// immediate check. The give-up deadline for each entry is
  /// `max(ackGiveUpWindow, tracking.timeout)`: the window acts as a floor
  /// and post-loop grace on fast presets, while on slow high-spreading-factor
  /// presets the deadline follows the attempt's own ACK wait so the checker
  /// never fires mid-attempt while the retry loop is still legitimately
  /// parked in `waitForEvent`.
  ///
  /// - Throws: Database errors when updating message status
  func checkExpiredAcks() async throws {
    let now = Date()
    let window = config.ackGiveUpWindow

    let expiredEntries = pendingAcks.filter { _, tracking in
      !tracking.isDelivered &&
        now.timeIntervalSince(tracking.sentAt) > max(window, tracking.timeout)
    }

    for (messageID, _) in expiredEntries {
      let didFail = try await dataStore.updateMessageStatusUnlessDelivered(id: messageID, status: .failed)
      guard let removed = pendingAcks.removeValue(forKey: messageID),
            !removed.isDelivered, didFail else { continue }

      let deadline = max(window, removed.timeout)
      logger.warning("[ack-diag] give-up: failed after \(String(format: "%.1f", now.timeIntervalSince(removed.sentAt)))s window=\(window)s deadline=\(String(format: "%.1f", deadline))s livePending=\(pendingAcks.count)")
      statusEventBroadcaster.yield(.failed(messageID: messageID))
    }
  }

  /// Fails all pending messages that are awaiting ACK.
  ///
  /// Use this when disconnecting from the device to mark all in-flight messages as failed.
  ///
  /// - Throws: Database errors when updating message status
  func failAllPendingMessages() async throws {
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
  func stopAndFailAllPending() async throws {
    ackCheckTask?.cancel()
    ackCheckTask = nil

    try await failAllPendingMessages()
  }

  /// The current number of pending ACKs being tracked.
  ///
  /// Includes undelivered messages still inside the `ackGiveUpWindow`.
  var pendingAckCount: Int {
    pendingAcks.count
  }

  /// Whether ACK expiry checking is currently active.
  var isAckExpiryCheckingActive: Bool {
    ackCheckTask != nil
  }
}

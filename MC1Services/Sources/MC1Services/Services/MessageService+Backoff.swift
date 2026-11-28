import Foundation

// MARK: - DM Backoff Rounds

extension MessageService {
  func setBackoffPolicy(_ policy: DMBackoffPolicy) {
    backoffPolicy = policy
  }

  func setBackoffScheduledHandler(_ handler: (@Sendable (Date) async -> Void)?) {
    backoffScheduledHandler = handler
  }

  /// When an opted-in DM's ACK window expires with rounds left, queues the next round instead of failing.
  /// The row waits in the durable send queue until its `notBefore` time, then sends once on a new timestamp.
  /// Returns true when a round was scheduled and the caller must not fail the message.
  func scheduleBackoffRetryIfOptedIn(messageID: UUID, tracking: PendingAck) async -> Bool {
    // Only an expiry with no acknowledgement evidence and a durably saved owner can back off.
    guard tracking.isAwaitingAck, let owner = tracking.owner, owner.phase == .saved else { return false }
    let plan: DMBackoffPlan?
    do {
      plan = try await dataStore.scheduleBackoffRetry(
        pendingSendID: owner.id,
        expectedDeadline: owner.deadline,
        policy: backoffPolicy,
        now: Date()
      )
    } catch {
      logger.warning("DM backoff scheduling failed messageID=\(messageID): \(String(describing: error))")
      return false
    }
    guard let plan else { return false }

    // The row is queued work again, so this send no longer holds the gate or an in-memory ACK entry.
    if pendingAcks[messageID]?.sentAt == tracking.sentAt {
      await discardTrackedAck(messageID: messageID)
    }
    logger.info("DM backoff round \(plan.round)/\(plan.maxRounds) messageID=\(messageID) notBefore=\(plan.notBefore)")
    statusEventBroadcaster.yield(.retrying(
      messageID: messageID, attempt: plan.retryAttempt, maxAttempts: plan.maxRetryAttempts
    ))
    await backoffScheduledHandler?(plan.notBefore)
    return true
  }
}

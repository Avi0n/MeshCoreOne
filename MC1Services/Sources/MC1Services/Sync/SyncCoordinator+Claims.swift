extension SyncCoordinator {
  /// Exceeds `SessionConfiguration.contactStreamHardTimeout` so large tables can finish,
  /// while a stalled advert cannot suspend foreground sync indefinitely.
  static let advertContactSyncWaitTimeout: Duration = .seconds(200)

  static let advertContactSyncWaitTimedOutMessage =
    "Timed out waiting for background contact sync"

  func claimOrdinarySync() async throws -> Bool {
    try await waitForAdvertContactSync()
    guard primarySyncClaim == nil else { return false }
    primarySyncClaim = .ordinary
    return true
  }

  func claimAdvertContactSync() -> Bool {
    guard primarySyncClaim == nil, !manualContactSyncActive else { return false }
    primarySyncClaim = .advert
    return true
  }

  func releaseSyncClaim() {
    primarySyncClaim = nil
    let waiters = advertSyncWaiters
    advertSyncWaiters = []
    for waiter in waiters {
      waiter.continuation.resume()
    }
  }

  /// Waits for advert ownership to clear; cancellation and timeout leave the claim intact.
  func waitForAdvertContactSync(
    timeout: Duration? = nil
  ) async throws {
    let bound = timeout
      ?? advertContactSyncWaitTimeoutOverride
      ?? Self.advertContactSyncWaitTimeout
    let deadline = ContinuousClock.now + bound

    while primarySyncClaim == .advert {
      try Task.checkCancellation()

      let remaining = deadline - ContinuousClock.now
      if remaining <= .zero {
        logger.warning("Timed out waiting for advert contact sync to release claim")
        throw SyncCoordinatorError.syncFailed(Self.advertContactSyncWaitTimedOutMessage)
      }

      let waiterID = nextAdvertSyncWaiterID
      nextAdvertSyncWaiterID &+= 1
      try await waitForAdvertClaimRelease(id: waiterID, timeout: remaining)
    }
  }

  /// Waits out an advert claim and sets manual inhibition in the same actor turn,
  /// so another advert cannot enter between the wait and claim.
  func claimManualContactSync(
    timeout: Duration? = nil
  ) async throws {
    try await waitForAdvertContactSync(timeout: timeout)
    manualContactSyncActive = true
  }

  func releaseManualContactSync() {
    manualContactSyncActive = false
  }

  func setAdvertContactSyncWaitTimeoutOverride(_ timeout: Duration?) {
    advertContactSyncWaitTimeoutOverride = timeout
  }

  private func waitForAdvertClaimRelease(id: UInt64, timeout: Duration) async throws {
    try await withThrowingTaskGroup(of: Void.self) { group in
      group.addTask {
        try await self.suspendUntilAdvertClaimReleased(id: id)
      }
      group.addTask {
        try await Task.sleep(for: timeout)
        throw SyncCoordinatorError.syncFailed(Self.advertContactSyncWaitTimedOutMessage)
      }
      try await group.next()
      group.cancelAll()
    }
  }

  private func suspendUntilAdvertClaimReleased(id: UInt64) async throws {
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        if Task.isCancelled {
          continuation.resume(throwing: CancellationError())
          return
        }
        // Claim may have cleared between the while check and registration.
        if primarySyncClaim != .advert {
          continuation.resume()
          return
        }
        advertSyncWaiters.append(AdvertSyncWaiter(id: id, continuation: continuation))
      }
    } onCancel: {
      Task { await self.finishAdvertSyncWaiter(id: id, error: CancellationError()) }
    }
  }

  private func finishAdvertSyncWaiter(id: UInt64, error: Error) {
    guard let index = advertSyncWaiters.firstIndex(where: { $0.id == id }) else { return }
    let waiter = advertSyncWaiters.remove(at: index)
    waiter.continuation.resume(throwing: error)
  }
}

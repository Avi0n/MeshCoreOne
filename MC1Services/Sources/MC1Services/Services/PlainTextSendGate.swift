import Foundation

/// Serializes plain-text sends until both command issuance and ACK tracking finish.
/// An early ACK cannot admit another sender while the current command is suspended.
actor PlainTextSendGate {
  struct Lease: Sendable, Hashable {
    let id: UUID
  }

  private struct Owner {
    let lease: Lease
    var issuingCommands: Bool
    var ackPending: Bool
    var sawResolution: Bool
  }

  private struct Waiter {
    let id: UUID
    let continuation: CheckedContinuation<Lease, Error>
  }

  private var owner: Owner?
  private var restoredAckLeaseIDs: Set<UUID> = []
  private var waiters: [Waiter] = []
  private(set) var isInvalidated = false

  private var canAdmit: Bool {
    owner == nil && restoredAckLeaseIDs.isEmpty
  }

  #if DEBUG
    var waiterCountForTesting: Int {
      waiters.count
    }

    var ownerLeaseIDForTesting: UUID? {
      owner?.lease.id
    }
  #endif

  func acquire() async throws -> Lease {
    try Task.checkCancellation()
    if isInvalidated { throw CancellationError() }
    if canAdmit {
      return claim()
    }

    let waiterID = UUID()
    return try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Lease, Error>) in
        if isInvalidated || Task.isCancelled {
          continuation.resume(throwing: CancellationError())
          return
        }
        waiters.append(Waiter(id: waiterID, continuation: continuation))
      }
    } onCancel: {
      Task { await self.cancelWaiter(waiterID) }
    }
  }

  func reserveRestoredAcks(_ leaseIDs: Set<UUID>) {
    guard !isInvalidated else { return }
    restoredAckLeaseIDs.formUnion(leaseIDs)
  }

  /// Preserves an earlier resolution when send bookkeeping arrives after an ACK.
  func noteAckObligation(_ lease: Lease) {
    guard var current = owner, current.lease == lease, !current.sawResolution else { return }
    current.ackPending = true
    owner = current
  }

  func settleAck(_ lease: Lease) {
    if restoredAckLeaseIDs.remove(lease.id) != nil {
      admitNext()
      return
    }
    guard var current = owner, current.lease == lease else { return }
    current.sawResolution = true
    current.ackPending = false
    owner = current
    releaseIfIdle()
  }

  /// The owner will issue no further commands. A pending ACK keeps the lease.
  func stopIssuing(_ lease: Lease) {
    guard var current = owner, current.lease == lease, current.issuingCommands else { return }
    current.issuingCommands = false
    owner = current
    releaseIfIdle()
  }

  /// Cancels admission without cancelling an in-flight command, whose caller still owns its completion.
  func invalidate() {
    isInvalidated = true
    owner = nil
    restoredAckLeaseIDs.removeAll()
    let pending = waiters
    waiters.removeAll()
    for waiter in pending {
      waiter.continuation.resume(throwing: CancellationError())
    }
  }

  private func claim() -> Lease {
    let lease = Lease(id: UUID())
    owner = Owner(lease: lease, issuingCommands: true, ackPending: false, sawResolution: false)
    return lease
  }

  private func releaseIfIdle() {
    guard let current = owner, !current.issuingCommands, !current.ackPending else { return }
    owner = nil
    admitNext()
  }

  private func admitNext() {
    guard !isInvalidated, canAdmit, !waiters.isEmpty else { return }
    let waiter = waiters.removeFirst()
    let lease = claim()
    waiter.continuation.resume(returning: lease)
  }

  private func cancelWaiter(_ id: UUID) {
    guard let index = waiters.firstIndex(where: { $0.id == id }) else { return }
    let waiter = waiters.remove(at: index)
    waiter.continuation.resume(throwing: CancellationError())
  }
}

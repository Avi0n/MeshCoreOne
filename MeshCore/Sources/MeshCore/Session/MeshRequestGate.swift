import Foundation
#if canImport(os)
  import os
#endif

/// Coordinates pending-reply mesh requests against companion firmware's single slot.
/// Background requests yield the queue to interactive requests.
actor MeshRequestGate {
  static let contactPrefixLength = 4

  static let minimumSameContactHold: TimeInterval = 1

  private let logger = Logger(subsystem: "MeshCore", category: "MeshRequestGate")
  private let clock: any Clock<Duration>
  private var isStopped = false
  private var holder: ActiveHolder?
  /// Set while a queued waiter has been resumed and has not taken the holder yet.
  private var admissionReserved = false
  private var interactiveWaiters: [Waiter] = []
  private var backgroundWaiters: [Waiter] = []
  /// In-flight reply windows, keyed by the 4-byte firmware contact prefix.
  private var sameContactHolds: [Data: SameContactHold] = [:]
  private var holdWake: Task<Void, Never>?

  var queuedWaiterCount: Int {
    interactiveWaiters.count + backgroundWaiters.count
  }

  var hasActiveHolder: Bool {
    holder != nil
  }

  var hasAnyActiveHold: Bool {
    for prefix in Array(sameContactHolds.keys) {
      _ = remainingHold(for: prefix)
    }
    return !sameContactHolds.isEmpty
  }

  func hasActiveHold(for contactPrefix: Data) -> Bool {
    remainingHold(for: contactPrefix) != nil
  }

  init(clock: any Clock<Duration>) {
    self.clock = clock
  }

  func acquire(
    priority: MeshRequestPriority,
    contactPrefix: Data,
    kind: MeshExchangeKind,
    preemptible: Bool = false,
    samePasswordDigest: Data? = nil,
    onPreempt: (@Sendable () -> Void)? = nil
  ) async throws -> MeshRequestTicket {
    guard !isStopped else { throw CancellationError() }
    try Task.checkCancellation()
    let request = Request(
      contactPrefix: contactPrefix,
      kind: kind,
      samePasswordDigest: samePasswordDigest
    )

    if priority == .interactive, var active = holder, active.preemptible, !active.preempted {
      active.preempted = true
      holder = active
      logger.info("Preempting active background request for interactive waiter")
      active.onPreempt?()
    }

    if holder == nil, !admissionReserved, isAdmissible(request, logDecision: true),
       priority == .interactive || interactiveWaiters.isEmpty {
      let ticket = MeshRequestTicket(contactPrefix: contactPrefix)
      holder = ActiveHolder(ticket: ticket, preemptible: preemptible, onPreempt: onPreempt)
      return ticket
    }

    if holder == nil, !admissionReserved, let blocking = blockingHold(for: request) {
      let prefixHex = contactPrefix.prefix(Self.contactPrefixLength).hexString
      logger.info("Delaying \(String(describing: kind)) to \(prefixHex) for hold \(blocking)")
    }

    let waiterID = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        guard !isStopped, !Task.isCancelled else {
          continuation.resume(throwing: CancellationError())
          return
        }
        let waiter = Waiter(
          id: waiterID,
          request: request,
          continuation: continuation
        )
        switch priority {
        case .interactive:
          interactiveWaiters.append(waiter)
        case .background:
          backgroundWaiters.append(waiter)
        }
        // A queued waiter can be blocked only by a hold, so make sure the hold's end wakes it.
        if holder == nil, !admissionReserved {
          admitNext()
        }
      }
    } onCancel: {
      Task { await self.cancelWaiter(waiterID) }
    }

    admissionReserved = false
    guard !isStopped else { throw CancellationError() }
    do {
      try Task.checkCancellation()
    } catch {
      admitNext()
      throw error
    }
    let ticket = MeshRequestTicket(contactPrefix: contactPrefix)
    var active = ActiveHolder(ticket: ticket, preemptible: preemptible, onPreempt: onPreempt)
    if preemptible, !interactiveWaiters.isEmpty {
      active.preempted = true
      logger.info("Preempting newly admitted background request for queued interactive waiter")
      onPreempt?()
    }
    holder = active
    return ticket
  }

  func release(_ ticket: MeshRequestTicket, sameContactHold: SameContactHoldRequest?) {
    guard holder?.ticket == ticket else { return }
    holder = nil
    guard !isStopped else { return }
    if let sameContactHold, sameContactHold.duration > .zero {
      let prefix = ticket.contactPrefix
      if let existing = activeHold(for: prefix) {
        let mergedDuration = max(existing.remaining, sameContactHold.duration)
        let mergedDigest: Data? = if let existingDigest = existing.loginPasswordDigest,
                                     existingDigest == sameContactHold.loginPasswordDigest {
          existingDigest
        } else {
          nil
        }
        sameContactHolds[prefix] = SameContactHold(
          remaining: clock.countdown(from: mergedDuration),
          loginPasswordDigest: mergedDigest
        )
      } else {
        sameContactHolds[prefix] = SameContactHold(
          remaining: clock.countdown(from: sameContactHold.duration),
          loginPasswordDigest: sameContactHold.loginPasswordDigest
        )
      }
    }
    admitNext()
  }

  func stop() {
    guard !isStopped else { return }
    isStopped = true
    holdWake?.cancel()
    holdWake = nil
    sameContactHolds.removeAll()
    let interactive = interactiveWaiters
    let background = backgroundWaiters
    interactiveWaiters.removeAll()
    backgroundWaiters.removeAll()
    for waiter in interactive {
      waiter.continuation.resume(throwing: CancellationError())
    }
    for waiter in background {
      waiter.continuation.resume(throwing: CancellationError())
    }
  }

  private func isAdmissible(_ request: Request, logDecision: Bool = false) -> Bool {
    guard request.kind.waitsOutSameContactReply else { return true }
    guard let hold = activeHold(for: request.contactPrefix) else { return true }
    if let exemptDigest = request.samePasswordDigest,
       let holdDigest = hold.loginPasswordDigest,
       exemptDigest == holdDigest {
      if logDecision {
        let prefixHex = request.contactPrefix.prefix(Self.contactPrefixLength).hexString
        logger.info("Same-password exemption bypassed hold for \(prefixHex)")
      }
      return true
    }
    return false
  }

  private func blockingHold(for request: Request) -> Duration? {
    guard request.kind.waitsOutSameContactReply else { return nil }
    guard let hold = activeHold(for: request.contactPrefix) else { return nil }
    if let exemptDigest = request.samePasswordDigest,
       let holdDigest = hold.loginPasswordDigest,
       exemptDigest == holdDigest {
      return nil
    }
    return hold.remaining
  }

  private func activeHold(for contactPrefix: Data) -> (remaining: Duration, loginPasswordDigest: Data?)? {
    guard let hold = sameContactHolds[contactPrefix] else { return nil }
    let remaining = hold.remaining()
    guard remaining > .zero else {
      sameContactHolds[contactPrefix] = nil
      return nil
    }
    return (remaining, hold.loginPasswordDigest)
  }

  private func remainingHold(for contactPrefix: Data) -> Duration? {
    activeHold(for: contactPrefix)?.remaining
  }

  private func admitNext() {
    guard !isStopped, holder == nil, !admissionReserved else { return }
    let next: Waiter? = if let index = interactiveWaiters.firstIndex(where: { isAdmissible($0.request, logDecision: true) }) {
      interactiveWaiters.remove(at: index)
    } else if interactiveWaiters.isEmpty,
              let index = backgroundWaiters.firstIndex(where: { isAdmissible($0.request, logDecision: true) }) {
      backgroundWaiters.remove(at: index)
    } else {
      nil
    }
    guard let next else {
      scheduleHoldWake()
      return
    }
    admissionReserved = true
    next.continuation.resume()
  }

  private func scheduleHoldWake() {
    holdWake?.cancel()
    holdWake = nil
    guard !isStopped else { return }
    let blocked = interactiveWaiters.isEmpty ? backgroundWaiters : interactiveWaiters
    let delays = blocked.compactMap { blockingHold(for: $0.request) }
    guard let delay = delays.min() else { return }
    holdWake = Task { [clock] in
      do {
        try await clock.sleep(for: delay)
      } catch {
        return
      }
      self.holdEnded()
    }
  }

  private func holdEnded() {
    holdWake = nil
    guard !isStopped else { return }
    admitNext()
  }

  private func cancelWaiter(_ id: UUID) {
    if let index = interactiveWaiters.firstIndex(where: { $0.id == id }) {
      interactiveWaiters.remove(at: index).continuation.resume(throwing: CancellationError())
    } else if let index = backgroundWaiters.firstIndex(where: { $0.id == id }) {
      backgroundWaiters.remove(at: index).continuation.resume(throwing: CancellationError())
    } else {
      return
    }
    // A cancelled interactive waiter may have been the only thing holding back background work.
    admitNext()
  }

  private struct Request {
    let contactPrefix: Data
    let kind: MeshExchangeKind
    let samePasswordDigest: Data?
  }

  private struct ActiveHolder {
    let ticket: MeshRequestTicket
    let preemptible: Bool
    let onPreempt: (@Sendable () -> Void)?
    var preempted = false
  }

  private struct Waiter {
    let id: UUID
    let request: Request
    let continuation: CheckedContinuation<Void, Error>
  }

  private struct SameContactHold {
    let remaining: @Sendable () -> Duration
    let loginPasswordDigest: Data?
  }
}

private extension Clock where Duration == Swift.Duration {
  func countdown(from duration: Duration) -> @Sendable () -> Duration {
    let start = now
    return { duration - start.duration(to: self.now) }
  }
}

struct SameContactHoldRequest: Sendable {
  let duration: Duration
  let loginPasswordDigest: Data?

  init(duration: Duration, loginPasswordDigest: Data? = nil) {
    self.duration = duration
    self.loginPasswordDigest = loginPasswordDigest
  }
}

struct MeshRequestTicket: Sendable, Equatable {
  let id: UUID
  let contactPrefix: Data

  init(contactPrefix: Data) {
    id = UUID()
    self.contactPrefix = contactPrefix
  }
}

public enum MeshRequestPriority: Sendable {
  case interactive
  case background
}

enum MeshExchangeKind: Sendable {
  case login
  case status
  case telemetry
  case binary
  case pathDiscovery

  /// Login and status replies are matched by public-key prefix, so a stray reply from this contact can complete them.
  var waitsOutSameContactReply: Bool {
    switch self {
    case .login, .status:
      true
    case .telemetry, .binary, .pathDiscovery:
      false
    }
  }
}

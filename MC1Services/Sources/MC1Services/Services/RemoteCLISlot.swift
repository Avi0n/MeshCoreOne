import Foundation

/// Holds a destination's FIFO slot across reply completion and recovery retries.
struct RemoteCLISlot {
  struct Request {
    let id: UUID
    let command: String
    let wirePrefix: String
    let acceptsAnyResponse: Bool
    let continuation: CheckedContinuation<String, Error>
    let worker: Task<Void, Never>
  }

  struct Waiter {
    let id: UUID
    let continuation: CheckedContinuation<Void, Error>
  }

  private enum State {
    case held
    case exchanging(Request)
  }

  private(set) var ownerID: UUID
  private var state: State = .held
  private var waiters: [Waiter] = []

  init(ownerID: UUID) {
    self.ownerID = ownerID
  }

  var pendingRequest: Request? {
    switch state {
    case let .exchanging(request): request
    case .held: nil
    }
  }

  var waiterCount: Int {
    waiters.count
  }

  mutating func beginExchange(_ request: Request) {
    switch state {
    case .held:
      state = .exchanging(request)
    case .exchanging:
      preconditionFailure("CLI submission requires an unoccupied exchange")
    }
  }

  func isCurrentRequest(requestID: UUID) -> Bool {
    pendingRequest?.id == requestID
  }

  /// Clear request ownership before cancelling the worker so cancellation cannot resume it again.
  mutating func claimCompletion(requestID: UUID) -> CheckedContinuation<String, Error>? {
    guard let request = pendingRequest, request.id == requestID else { return nil }
    state = .held
    request.worker.cancel()
    return request.continuation
  }

  mutating func enqueue(_ waiter: Waiter) {
    waiters.append(waiter)
  }

  mutating func removeWaiter(id: UUID) -> Waiter? {
    guard let index = waiters.firstIndex(where: { $0.id == id }) else { return nil }
    return waiters.remove(at: index)
  }

  mutating func advance() -> Waiter? {
    precondition(pendingRequest == nil, "CLI handoff requires a completed exchange")
    guard !waiters.isEmpty else { return nil }
    let next = waiters.removeFirst()
    ownerID = next.id
    return next
  }

  mutating func cancelAll() {
    if let request = pendingRequest {
      claimCompletion(requestID: request.id)?.resume(throwing: CancellationError())
    }
    let cancelled = waiters
    waiters.removeAll()
    for waiter in cancelled {
      waiter.continuation.resume(throwing: CancellationError())
    }
  }
}

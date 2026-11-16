import Synchronization

/// Revocable command admission for one session over a caller-owned transport.
public final class SessionTransportBinding: Sendable {
  private struct State {
    var isRevoked = false
    var operations = 0
    var waiters: [CheckedContinuation<Void, Never>] = []
  }

  private let state = Mutex(State())

  public init() {}

  public var isActive: Bool {
    state.withLock { !$0.isRevoked }
  }

  #if DEBUG
    var quiescenceWaiterCountForTesting: Int {
      state.withLock { $0.waiters.count }
    }
  #endif

  public func revoke() {
    state.withLock { $0.isRevoked = true }
  }

  public func checkActive() throws {
    guard isActive else { throw CancellationError() }
  }

  /// Linearizes physical submission with revocation; submitted work must still settle.
  public func performIfActive(_ submission: () throws -> Void) throws {
    try state.withLock { state in
      guard !state.isRevoked else { throw CancellationError() }
      try submission()
    }
  }

  func beginOperation() throws {
    try state.withLock {
      guard !$0.isRevoked else { throw CancellationError() }
      $0.operations += 1
    }
  }

  func endOperation() {
    let waiters = state.withLock { state -> [CheckedContinuation<Void, Never>] in
      state.operations -= 1
      guard state.operations == 0 else { return [] }
      let waiters = state.waiters
      state.waiters.removeAll()
      return waiters
    }
    for waiter in waiters {
      waiter.resume()
    }
  }

  /// Joins actual transport calls, including calls whose original waiter was cancelled.
  public func waitForQuiescence() async {
    await withCheckedContinuation { continuation in
      let complete = state.withLock { state -> Bool in
        guard state.operations != 0 else { return true }
        state.waiters.append(continuation)
        return false
      }
      if complete { continuation.resume() }
    }
  }
}

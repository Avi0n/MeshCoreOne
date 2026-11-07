import Foundation
import MeshCore

/// Owns one login until its caller finishes, including persistence after a reply.
struct RemoteLoginAttempt {
  struct Completion {
    let attemptID: UUID
    let sessionID: UUID
    let continuation: CheckedContinuation<LoginResult, Error>
  }

  private struct Pending {
    let continuation: CheckedContinuation<LoginResult, Error>
    let worker: Task<Void, Never>
  }

  private enum State {
    case preparing
    case submitting(Pending)
    case awaitingResponse(Pending)
    case completing
  }

  let id = UUID()
  let sessionID: UUID
  private var state: State = .preparing

  init(sessionID: UUID) {
    self.sessionID = sessionID
  }

  var isAwaitingResult: Bool {
    switch state {
    case .submitting, .awaitingResponse: true
    case .preparing, .completing: false
    }
  }

  mutating func beginSubmission(
    continuation: CheckedContinuation<LoginResult, Error>, worker: Task<Void, Never>
  ) {
    guard case .preparing = state else {
      preconditionFailure("Login submission requires a preparing attempt")
    }
    state = .submitting(Pending(continuation: continuation, worker: worker))
  }

  @discardableResult
  mutating func didSubmit() -> Bool {
    guard case let .submitting(pending) = state else { return false }
    state = .awaitingResponse(pending)
    return true
  }

  /// Transfers completion before cancelling the worker; an early reply can win during submission.
  mutating func claimCompletion() -> Completion? {
    let pending: Pending
    switch state {
    case let .submitting(value), let .awaitingResponse(value):
      pending = value
    case .preparing, .completing:
      return nil
    }
    state = .completing
    pending.worker.cancel()
    return Completion(attemptID: id, sessionID: sessionID, continuation: pending.continuation)
  }
}

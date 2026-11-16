/// Owns one Trace request, independently of batch scheduling and result presentation.
enum TraceRequestState {
  case idle
  case sending(Submission)
  case waiting(Submission, Wait)

  var submission: Submission? {
    switch self {
    case .idle:
      nil
    case let .sending(submission), let .waiting(submission, _):
      submission
    }
  }

  var timeoutTask: Task<Void, Never>? {
    guard case let .waiting(_, wait) = self else { return nil }
    return wait.timeoutTask
  }

  /// Clears ownership before releasing the waiter so a late event cannot finish twice.
  mutating func finish() {
    let previous = self
    self = .idle
    if case let .waiting(_, wait) = previous {
      wait.timeoutTask.cancel()
      wait.continuation?.resume()
    }
  }
}

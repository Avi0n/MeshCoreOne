import Foundation

extension TraceRequestState {
  struct Wait {
    let deadline: Date
    let timeoutTask: Task<Void, Never>
    var continuation: CheckedContinuation<Void, Never>?
  }
}

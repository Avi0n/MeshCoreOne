import Foundation

extension TraceRequestState {
  struct Submission {
    let input: Input
    let tag: UInt32
    let radioID: UUID?
    let startedAt: Date
  }
}

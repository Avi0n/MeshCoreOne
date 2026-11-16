import Foundation
@testable import MC1Services
import os

final class BLETestOperationClock: Sendable {
  private struct Sleeper {
    let id: UUID
    let deadline: ContinuousClock.Instant
    let continuation: CheckedContinuation<Void, Error>
  }

  private struct State {
    var now = ContinuousClock.now
    var sleepers: [Sleeper] = []
  }

  private let state = OSAllocatedUnfairLock(initialState: State())

  var clock: BLEOperationClock {
    BLEOperationClock(now: { self.state.withLock { $0.now } }, sleep: { try await self.sleep(for: $0) })
  }

  var sleeperCount: Int {
    state.withLock { $0.sleepers.count }
  }

  func advance(by duration: Duration) {
    let ready = state.withLock { state in
      state.now = state.now.advanced(by: duration)
      let ready = state.sleepers.filter { $0.deadline <= state.now }
      state.sleepers.removeAll { $0.deadline <= state.now }
      return ready
    }
    ready.forEach { $0.continuation.resume() }
  }

  private func sleep(for duration: Duration) async throws {
    guard duration > .zero else { return }
    let id = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        let cancelled = state.withLock { state in
          guard !Task.isCancelled else { return true }
          state.sleepers.append(Sleeper(id: id, deadline: state.now.advanced(by: duration), continuation: continuation))
          return false
        }
        if cancelled { continuation.resume(throwing: CancellationError()) }
      }
    } onCancel: {
      let sleeper = self.state.withLock { state -> Sleeper? in
        guard let index = state.sleepers.firstIndex(where: { $0.id == id }) else { return nil }
        return state.sleepers.remove(at: index)
      }
      sleeper?.continuation.resume(throwing: CancellationError())
    }
  }
}

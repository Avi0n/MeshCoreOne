import Foundation

/// A deterministic `Clock` for tests. Virtual time advances only through
/// `advance(by:)`; `sleep(until:)` suspends until virtual time reaches the
/// deadline (or returns immediately if the deadline has already passed).
///
/// Cancel matches `ContinuousClock`: `sleep` throws `CancellationError`.
///
/// `sleeperCount` lets a test confirm the code under test has actually parked
/// on this clock before advancing, so the two never race. If production stops
/// using the injected clock, `sleeperCount` stays zero and the waiting test
/// fails instead of silently passing on wall-clock timing.
final class TestClock: Clock, @unchecked Sendable {
  struct Instant: InstantProtocol {
    var offset: Duration

    func advanced(by duration: Duration) -> Instant {
      Instant(offset: offset + duration)
    }

    func duration(to other: Instant) -> Duration {
      other.offset - offset
    }

    static func < (lhs: Instant, rhs: Instant) -> Bool {
      lhs.offset < rhs.offset
    }
  }

  var minimumResolution: Duration {
    .zero
  }

  private let lock = NSLock()
  private var _now = Instant(offset: .zero)
  private var sleepers: [Sleeper] = []

  private struct Sleeper {
    let id: UUID
    let deadline: Instant
    let continuation: CheckedContinuation<Void, any Error>
  }

  var now: Instant {
    lock.lock()
    defer { lock.unlock() }
    return _now
  }

  /// Number of tasks currently parked in `sleep`.
  var sleeperCount: Int {
    lock.lock()
    defer { lock.unlock() }
    return sleepers.count
  }

  func sleep(until deadline: Instant, tolerance: Duration? = nil) async throws {
    let id = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
        lock.lock()
        if Task.isCancelled {
          lock.unlock()
          continuation.resume(throwing: CancellationError())
          return
        }
        if deadline <= _now {
          lock.unlock()
          continuation.resume()
          return
        }
        sleepers.append(Sleeper(id: id, deadline: deadline, continuation: continuation))
        lock.unlock()
      }
    } onCancel: {
      lock.lock()
      guard let index = sleepers.firstIndex(where: { $0.id == id }) else {
        lock.unlock()
        return
      }
      let sleeper = sleepers.remove(at: index)
      lock.unlock()
      sleeper.continuation.resume(throwing: CancellationError())
    }
  }

  /// Advances virtual time, resuming every sleeper whose deadline has passed.
  func advance(by duration: Duration = .zero) {
    lock.lock()
    _now = _now.advanced(by: duration)
    let due = sleepers.filter { $0.deadline <= _now }
    sleepers.removeAll { $0.deadline <= _now }
    lock.unlock()
    for sleeper in due {
      sleeper.continuation.resume()
    }
  }
}

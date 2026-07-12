import Foundation
@testable import MC1Services
import Testing

@Suite("BLETransportOpenedSignal")
struct BLETransportOpenedSignalTests {
  @Test
  func `wait returns immediately when signal already armed`() async throws {
    let triggers = BLETransportOpenedSignal()
    await triggers.fire()
    try await triggers.wait()
  }

  @Test
  func `wait suspends until fire`() async throws {
    let triggers = BLETransportOpenedSignal()
    let waiting = Task {
      try await triggers.wait()
    }
    try? await Task.sleep(for: .milliseconds(50))
    await triggers.fire()
    try await waiting.value
  }

  /// A `wait` whose calling task is cancelled before fire lands must
  /// throw `CancellationError` rather than leaking the continuation.
  @Test
  func `wait throws CancellationError when calling task is cancelled`() async {
    let triggers = BLETransportOpenedSignal()
    let task = Task {
      try await triggers.wait()
    }
    try? await Task.sleep(for: .milliseconds(50))
    task.cancel()

    do {
      try await task.value
      Issue.record("wait should have thrown CancellationError")
    } catch is CancellationError {
      // expected
    } catch {
      Issue.record("wait threw unexpected error: \(error)")
    }
  }

  /// Regression: after a cancellation removes a waiter, a subsequent
  /// `fire()` must not double-resume that continuation. The cancellation
  /// path removes its own waiter before throwing, so `fire()` sees a
  /// clean waiter list. The fired signal is preserved as armed for the
  /// next caller (no waiter consumed it).
  @Test
  func `fire after cancellation does not double-resume`() async throws {
    let triggers = BLETransportOpenedSignal()
    let task = Task {
      try await triggers.wait()
    }
    try? await Task.sleep(for: .milliseconds(50))
    task.cancel()
    _ = try? await task.value

    // If `fire` tried to resume the already-cancelled continuation,
    // `withCheckedThrowingContinuation` would trap (precondition: resume
    // exactly once). Reaching the next assertion at all proves no
    // double-resume happened.
    await triggers.fire()

    // Signal should be armed for the next caller since no waiter
    // consumed it.
    try await triggers.wait()
  }
}

import Foundation
import Testing

@Suite("TestClock cancellation")
struct TestClockTests {
  @Test
  func `already-cancelled sleep throws and does not park`() async {
    let clock = TestClock()
    let task = Task {
      try await clock.sleep(for: .seconds(2))
    }
    task.cancel()
    var threwCancellation = false
    do {
      try await task.value
    } catch is CancellationError {
      threwCancellation = true
    } catch {
      Issue.record("expected CancellationError, got \(error)")
    }
    #expect(threwCancellation)
    #expect(clock.sleeperCount == 0)
  }

  @Test
  func `cancel after park throws and clears sleeperCount`() async throws {
    let clock = TestClock()
    let task = Task {
      try await clock.sleep(for: .seconds(2))
    }
    try await waitUntil("sleep should park") {
      clock.sleeperCount == 1
    }
    task.cancel()
    var threwCancellation = false
    do {
      try await task.value
    } catch is CancellationError {
      threwCancellation = true
    } catch {
      Issue.record("expected CancellationError, got \(error)")
    }
    #expect(threwCancellation)
    #expect(clock.sleeperCount == 0)
  }

  @Test
  func `advance still wakes an uncancelled sleeper`() async throws {
    let clock = TestClock()
    let task = Task {
      try await clock.sleep(for: .seconds(2))
    }
    try await waitUntil("sleep should park") {
      clock.sleeperCount == 1
    }
    clock.advance(by: .seconds(2))
    try await task.value
    #expect(clock.sleeperCount == 0)
  }
}

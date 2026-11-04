import Foundation
@testable import MC1Services
import Testing

@Suite("Reconnect retry completion ownership")
@MainActor
struct BLEReconnectionCoordinatorOwnershipPortTests {
  enum Outcome: CaseIterable {
    case success
    case failure
    case cancellation
  }

  @Test(arguments: Outcome.allCases)
  func `held second rebuild cannot change a newer same-radio cycle`(outcome: Outcome) async throws {
    try await withReconnectionHarness { harness in
      let old = harness.begin()
      let oldGate = harness.gate()
      let newGate = harness.gate()
      var oldCalls = 0
      var secondEntered = false
      harness.coordinator.onRebuild = { cycle in
        harness.rebuilds.append(cycle)
        if cycle == old {
          oldCalls += 1
          if oldCalls == 1 { throw BLEReconnectionTestHarness.RebuildError.failed }
          secondEntered = true
          await oldGate.wait()
          switch outcome {
          case .success: return
          case .failure: throw BLEReconnectionTestHarness.RebuildError.failed
          case .cancellation: throw CancellationError()
          }
        }
        await newGate.wait()
      }
      let oldCompletion = harness.complete(old)
      try await waitUntil("first failed rebuild should enter its retry delay") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(2))
      try await waitUntil("the second rebuild must enter before supersession") { secondEntered }
      let next = harness.begin(deviceID: old.deviceID, requestID: old.requestID)
      let nextCompletion = harness.complete(next)
      try await waitUntil("the new cycle must own its held rebuild") { harness.rebuilds == [old, old, next] }
      oldGate.open()
      await oldCompletion.value
      #expect(harness.coordinator.currentCycle == next)
      #expect(harness.coordinator.isRebuilding(next))
      #expect(harness.exhausted.isEmpty)
      #expect(harness.timeouts.isEmpty)
      #expect(harness.clock.sleeperCount == 0)
      newGate.open()
      await nextCompletion.value
      #expect(harness.coordinator.currentCycle == nil)
      #expect(harness.exhausted.isEmpty)
    }
  }
}

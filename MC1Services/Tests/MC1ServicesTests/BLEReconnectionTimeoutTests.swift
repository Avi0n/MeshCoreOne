import Foundation
@testable import MC1Services
import Testing

@Suite("BLE reconnect presentation timeout")
@MainActor
struct BLEReconnectionTimeoutTests {
  @Test
  func `timeout ends a cycle when OS auto-reconnect has stopped`() async throws {
    try await withReconnectionHarness(uiTimeout: 1) { harness in
      let cycle = harness.begin()
      harness.coordinator.armTimeout(cycle)
      try await waitUntil("timeout should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(1))
      try await waitUntil("timeout should be delivered") { harness.timeouts == [cycle] }
      #expect(harness.coordinator.currentCycle == nil)
      await harness.coordinator.complete(cycle)
      #expect(harness.rebuilds.isEmpty)
    }
  }

  @Test
  func `OS auto-reconnect accepts late completion after the UI window ends`() async throws {
    try await withReconnectionHarness(uiTimeout: 1, maxUIWindow: 1) { harness in
      harness.autoReconnecting = true
      let cycle = harness.begin()
      harness.coordinator.armTimeout(cycle)
      try await waitUntil("timeout should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(1))
      try await waitUntil("UI timeout should be delivered") { harness.timeouts == [cycle] }
      #expect(harness.coordinator.currentCycle == cycle)
      await harness.coordinator.complete(cycle)
      #expect(harness.rebuilds == [cycle])
      #expect(harness.coordinator.currentCycle == nil)
    }
  }

  @Test
  func `active OS auto-reconnect rearms only inside the maximum UI window`() async throws {
    try await withReconnectionHarness(uiTimeout: 1, maxUIWindow: 3) { harness in
      harness.autoReconnecting = true
      let cycle = harness.begin()
      harness.coordinator.armTimeout(cycle)
      for _ in 0..<2 {
        try await waitUntil("timeout should park before advancing") { harness.clock.sleeperCount == 1 }
        harness.clock.advance(by: .seconds(1))
        try await waitUntil("active auto-reconnect should rearm") { harness.clock.sleeperCount == 1 }
        #expect(harness.timeouts.isEmpty)
      }
      harness.clock.advance(by: .seconds(1))
      try await waitUntil("maximum window should end the presentation") { harness.timeouts == [cycle] }
      #expect(harness.coordinator.currentCycle == cycle)
      #expect(harness.clock.sleeperCount == 0)
    }
  }

  @Test
  func `a timeout query returning after replacement cannot expire the newer cycle`() async throws {
    try await withReconnectionHarness(uiTimeout: 1) { harness in
      let old = harness.begin()
      let gate = harness.gate()
      var queryEntered = false
      harness.onAutoReconnectQuery = {
        queryEntered = true
        await gate.wait()
      }
      harness.coordinator.armTimeout(old)
      try await waitUntil("old timeout should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(1))
      try await waitUntil("old timeout should enter its transport query") { queryEntered }
      let next = harness.begin()
      harness.coordinator.armTimeout(next)
      harness.onAutoReconnectQuery = nil
      gate.open()
      try await waitUntil("new timeout should remain armed") { harness.clock.sleeperCount == 1 }
      #expect(harness.coordinator.currentCycle == next)
      #expect(harness.timeouts.isEmpty)
      await harness.coordinator.complete(next)
      #expect(harness.rebuilds == [next])
    }
  }

  @Test
  func `completion during the timeout transport query suppresses its stale result`() async throws {
    try await withReconnectionHarness(uiTimeout: 1) { harness in
      let cycle = harness.begin()
      harness.onAutoReconnectQuery = { await harness.coordinator.complete(cycle) }
      harness.coordinator.armTimeout(cycle)
      try await waitUntil("timeout should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(1))
      try await waitUntil("rebuild should complete during the query") {
        harness.rebuilds == [cycle] && harness.coordinator.currentCycle == nil
      }
      #expect(harness.timeouts.isEmpty)
      #expect(harness.clock.sleeperCount == 0)
    }
  }

  @Test
  func `late timeout arming after completion leaves no timer`() async {
    await withReconnectionHarness { harness in
      let cycle = harness.begin()
      await harness.coordinator.complete(cycle)
      harness.coordinator.armTimeout(cycle)
      #expect(harness.clock.sleeperCount == 0)
      #expect(harness.coordinator.currentCycle == nil)
    }
  }

  @Test
  func `late entry cannot arm a timer while its rebuild is in flight`() async throws {
    try await withReconnectionHarness { harness in
      let cycle = harness.begin()
      let gate = harness.gate()
      harness.onRebuild = { _ in await gate.wait() }
      let completion = harness.complete(cycle)
      try await waitUntil("rebuild should enter its gate") { harness.rebuilds == [cycle] }
      harness.coordinator.armTimeout(cycle)
      #expect(harness.clock.sleeperCount == 0)
      gate.open()
      await completion.value
    }
  }

  @Test
  func `cancelTimeout drains the timer without ending its cycle`() async throws {
    try await withReconnectionHarness(uiTimeout: 1) { harness in
      let cycle = harness.begin()
      harness.coordinator.armTimeout(cycle)
      try await waitUntil("timeout should park") { harness.clock.sleeperCount == 1 }
      harness.coordinator.cancelTimeout()
      #expect(harness.clock.sleeperCount == 0)
      harness.clock.advance(by: .seconds(1))
      #expect(harness.timeouts.isEmpty)
      #expect(harness.coordinator.currentCycle == cycle)
    }
  }

  @Test
  func `restartTimeout preserves the request and resets only its UI window`() async throws {
    try await withReconnectionHarness(uiTimeout: 1, maxUIWindow: 3) { harness in
      harness.autoReconnecting = true
      let cycle = harness.begin()
      harness.coordinator.armTimeout(cycle)
      try await waitUntil("timeout should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(2))
      try await waitUntil("timeout should rearm") { harness.clock.sleeperCount == 1 }
      harness.coordinator.restartTimeout(deviceID: cycle.deviceID)
      try await waitUntil("restarted timeout should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(2))
      try await waitUntil("restarted UI window should still rearm") { harness.clock.sleeperCount == 1 }
      #expect(harness.timeouts.isEmpty)
      #expect(harness.coordinator.currentCycle == cycle)
      harness.clock.advance(by: .seconds(1))
      try await waitUntil("restarted maximum window should end") { harness.timeouts == [cycle] }
    }
  }

  @Test
  func `restartTimeout never invents a request or replaces another cycle`() async {
    await withReconnectionHarness { harness in
      harness.coordinator.restartTimeout(deviceID: UUID())
      #expect(harness.coordinator.currentCycle == nil)
      let cycle = harness.begin()
      harness.coordinator.restartTimeout(deviceID: UUID())
      #expect(harness.coordinator.currentCycle == cycle)
      #expect(harness.clock.sleeperCount == 0)
    }
  }

  @Test
  func `stale timeout arming preserves the current timer`() async throws {
    try await withReconnectionHarness(uiTimeout: 1) { harness in
      let old = harness.begin()
      let next = harness.begin()
      harness.coordinator.armTimeout(next)
      try await waitUntil("current timeout should park") { harness.clock.sleeperCount == 1 }
      harness.coordinator.armTimeout(old)
      harness.clock.advance(by: .seconds(1))
      try await waitUntil("only current timeout should be delivered") { harness.timeouts == [next] }
    }
  }
}

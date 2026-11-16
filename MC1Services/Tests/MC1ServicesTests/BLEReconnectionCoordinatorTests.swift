import Foundation
@testable import MC1Services
import Testing

@Suite("BLEReconnectionCoordinator Tests")
@MainActor
struct BLEReconnectionCoordinatorTests {
  @Test
  func `begin captures the request and each same-radio cycle is distinct`() async {
    await withReconnectionHarness { harness in
      let deviceID = UUID()
      let requestID = UUID()
      let first = harness.begin(deviceID: deviceID, requestID: requestID)
      let second = harness.begin(deviceID: deviceID, requestID: requestID)

      #expect(first.deviceID == deviceID)
      #expect(first.requestID == requestID)
      #expect(first.id != second.id)
      #expect(first.generation < second.generation)
      #expect(!harness.coordinator.isCurrent(first))
      #expect(harness.coordinator.currentCycle == second)
      #expect(harness.clock.sleeperCount == 0)
      #expect(harness.rebuilds.isEmpty && harness.timeouts.isEmpty && harness.exhausted.isEmpty)
    }
  }

  @Test
  func `completion during reconnect notification preserves the rebuilt session`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.bluetooth)
      let request = try #require(env.manager.desiredRequest)
      let previous = try #require(env.manager.currentConnection)
      let origin = try #require(await env.ble.lifecycleOrigin)
      env.manager.onAutoReconnectStarted = { _ in
        let cycle = try? #require(env.manager.reconnectionCoordinator.currentCycle)
        if let cycle { await env.manager.reconnectionCoordinator.complete(cycle) }
      }

      await env.manager.enterBLERecovery(request, origin: origin)

      #expect(env.manager.connectionState == .ready)
      #expect(env.manager.connectionRequestID == previous.requestID)
      #expect(env.manager.currentConnection?.generation != previous.generation)
      #expect(env.manager.reconnectionCoordinator.currentCycle == nil)
      #expect(await env.ble.connectCount == 1)
      #expect(try await env.manager.session?.getTime() != nil)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test
  func `completion only rebuilds the current cycle`() async {
    await withReconnectionHarness { harness in
      let first = harness.begin()
      let second = harness.begin()
      await harness.coordinator.complete(first)
      #expect(harness.rebuilds.isEmpty)
      #expect(harness.coordinator.currentCycle == second)

      await harness.coordinator.complete(second)
      #expect(harness.rebuilds == [second])
      #expect(harness.coordinator.currentCycle == nil)
      await harness.coordinator.complete(second)
      #expect(harness.rebuilds == [second])
    }
  }

  @Test
  func `overlapping completions start one rebuild and preserve subsequent liveness`() async throws {
    try await withReconnectionHarness { harness in
      let cycle = harness.begin()
      let gate = harness.gate()
      harness.onRebuild = { _ in await gate.wait() }
      let first = harness.complete(cycle)
      try await waitUntil("first rebuild should enter its gate") { harness.rebuilds == [cycle] }
      await harness.coordinator.complete(cycle)
      #expect(harness.coordinator.currentCycle == cycle)
      #expect(harness.rebuilds == [cycle])
      gate.open()
      await first.value

      harness.onRebuild = nil
      let next = harness.begin()
      await harness.coordinator.complete(next)
      #expect(harness.rebuilds == [cycle, next])
      #expect(harness.coordinator.currentCycle == nil)
    }
  }

  @Test
  func `first rebuild failure retries after two seconds`() async throws {
    try await withReconnectionHarness { harness in
      harness.remainingFailures = 1
      let cycle = harness.begin()
      let completion = harness.complete(cycle)
      try await waitUntil("retry should wait on the injected clock") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(1))
      #expect(harness.rebuilds == [cycle])
      #expect(harness.coordinator.currentCycle == cycle)
      harness.clock.advance(by: .seconds(1))
      await completion.value
      #expect(harness.rebuilds == [cycle, cycle])
      #expect(harness.exhausted.isEmpty)
      #expect(harness.coordinator.currentCycle == nil)
    }
  }

  @Test
  func `retry exhaustion holds the cycle through cleanup then releases it`() async throws {
    try await withReconnectionHarness { harness in
      harness.remainingFailures = 2
      let cycle = harness.begin()
      var claimDuringExhaustion: BLEReconnectionCoordinator.Cycle?
      harness.onExhausted = { _ in
        claimDuringExhaustion = harness.coordinator.currentCycle
        harness.coordinator.clear()
      }
      let completion = harness.complete(cycle)
      try await waitUntil("retry should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(2))
      await completion.value
      #expect(claimDuringExhaustion == cycle)
      #expect(harness.exhausted == [cycle])
      #expect(harness.coordinator.currentCycle == nil)
      let next = harness.begin()
      await harness.coordinator.complete(next)
      #expect(harness.rebuilds == [cycle, cycle, next])
    }
  }

  @Test
  func `same-cycle completion during retry does not admit another rebuild`() async throws {
    try await withReconnectionHarness { harness in
      harness.remainingFailures = 2
      let cycle = harness.begin()
      let completion = harness.complete(cycle)
      try await waitUntil("retry should park") { harness.clock.sleeperCount == 1 }
      await harness.coordinator.complete(cycle)
      #expect(harness.rebuilds == [cycle])
      #expect(harness.coordinator.currentCycle == cycle)
      harness.clock.advance(by: .seconds(2))
      await completion.value
      #expect(harness.rebuilds == [cycle, cycle])
      #expect(harness.exhausted == [cycle])
    }
  }

  @Test
  func `a newer cycle cancels a sleeping retry without clearing its claim`() async throws {
    try await withReconnectionHarness { harness in
      harness.remainingFailures = 1
      let old = harness.begin()
      let completion = harness.complete(old)
      try await waitUntil("old retry should park") { harness.clock.sleeperCount == 1 }
      let next = harness.begin(deviceID: old.deviceID, requestID: old.requestID)
      await completion.value
      #expect(harness.clock.sleeperCount == 0)
      #expect(harness.coordinator.currentCycle == next)
      #expect(harness.exhausted.isEmpty)
      await harness.coordinator.complete(next)
      #expect(harness.rebuilds == [old, next])
    }
  }

  @Test
  func `old held rebuild completion cannot clear a newer in-flight cycle`() async throws {
    try await withReconnectionHarness { harness in
      let old = harness.begin()
      let oldGate = harness.gate()
      let newGate = harness.gate()
      harness.onRebuild = { cycle in
        if cycle == old {
          await oldGate.wait()
        } else {
          await newGate.wait()
        }
      }
      let oldCompletion = harness.complete(old)
      try await waitUntil("old rebuild should enter") { harness.rebuilds == [old] }
      let next = harness.begin()
      let newCompletion = harness.complete(next)
      try await waitUntil("new rebuild should enter") { harness.rebuilds == [old, next] }
      oldGate.open()
      await oldCompletion.value
      #expect(harness.coordinator.currentCycle == next)
      await harness.coordinator.complete(next)
      #expect(harness.rebuilds == [old, next])
      newGate.open()
      await newCompletion.value
      #expect(harness.coordinator.currentCycle == nil)
      #expect(harness.exhausted.isEmpty)
    }
  }

  @Test
  func `clear during a held failed rebuild suppresses retry and exhaustion`() async throws {
    try await withReconnectionHarness { harness in
      harness.remainingFailures = 1
      let cycle = harness.begin()
      let gate = harness.gate()
      harness.onRebuild = { _ in await gate.wait() }
      let completion = harness.complete(cycle)
      try await waitUntil("rebuild should enter") { harness.rebuilds == [cycle] }
      harness.coordinator.clear()
      gate.open()
      await completion.value
      #expect(harness.rebuilds == [cycle])
      #expect(harness.exhausted.isEmpty)
      #expect(harness.clock.sleeperCount == 0)
      #expect(harness.coordinator.currentCycle == nil)
    }
  }

  @Test
  func `supersession during exhaustion preserves the newer cycle`() async throws {
    try await withReconnectionHarness { harness in
      harness.remainingFailures = 2
      let cycle = harness.begin()
      var next: BLEReconnectionCoordinator.Cycle?
      harness.onExhausted = { _ in next = harness.begin() }
      let completion = harness.complete(cycle)
      try await waitUntil("retry should park") { harness.clock.sleeperCount == 1 }
      harness.clock.advance(by: .seconds(2))
      await completion.value
      #expect(harness.coordinator.currentCycle == next)
      #expect(next != nil)
    }
  }
}

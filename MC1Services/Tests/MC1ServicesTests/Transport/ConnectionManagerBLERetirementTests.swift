@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("Retirement manager BLE")
@MainActor
struct ConnectionManagerBLERetirementTests {
  enum Action: CaseIterable, Sendable { case disconnect, wifiSwitch, preservedRebuild }

  @Test(arguments: Action.allCases)
  func `actual manager retires its first unsent BLE pipeline`(action: Action) async throws {
    let fixture = try await RetirementBLEManagerHarness.make()
    let machine = fixture.ble.machine
    let adapter = fixture.ble.adapter
    var pipeline: Task<(received: [ChannelInfo], missing: [UInt8]), Error>?
    var terminal: Task<Void, Error>?
    do {
      try await fixture.connect()
      let old = try #require(fixture.manager.currentConnection)
      let session = try #require(fixture.manager.session)
      let before = fixture.ble.native.writePayloads
      let origin = try #require(await adapter.lifecycleOrigin)
      fixture.ble.native.isReadyToWrite = false
      pipeline = Task { try await session.getChannels(indices: [0, 1]) }
      try await fixture.ble.awaitCondition { await machine.isAwaitingWriteWithoutResponseReady }
      let pending = try #require(pipeline)
      let completed = OSAllocatedUnfairLock(initialState: false)
      terminal = Task {
        switch action {
        case .disconnect:
          _ = await fixture.manager.disconnect(reason: .userInitiated)
        case .wifiSwitch:
          _ = try await fixture.manager.connectViaWiFi(host: "other.local", port: 4242)
        case .preservedRebuild:
          await fixture.manager.handleBLELifecycleEvent(.autoReconnecting(origin, errorInfo: "recovery"))
        }
        _ = await pending.result
        #expect(fixture.ble.native.writePayloads.filter { $0.first == CommandCode.getChannel.rawValue }
          == before.filter { $0.first == CommandCode.getChannel.rawValue })
        if action == .preservedRebuild {
          fixture.ble.native.isReadyToWrite = true
          await fixture.manager.handleBLELifecycleEvent(.reconnected(origin))
        }
        completed.withLock { $0 = true }
      }
      let prompt = await fixture.ble.observedBeforeDeadline { completed.withLock { $0 } }
      #expect(prompt, "Actual manager retirement cannot wait for unsent-frame readiness or its long watchdog")
      guard prompt else { throw BLESetupRetirementHarness.WaitError.expired }
      let retiring = try #require(terminal)
      try await retiring.value
      #expect(await session.subscriberCountForTest == 0)
      if action == .disconnect {
        _ = try await fixture.manager.connectViaWiFi(host: "other.local", port: 4242)
      }
      #expect(fixture.manager.connectionState == .ready)
      #expect(fixture.manager.currentConnection?.generation != old.generation)
      let successor = try #require(fixture.manager.session)
      _ = try await successor.getTime()
    } catch {
      fixture.ble.native.isReadyToWrite = true
      await machine.shutdown()
      _ = await pipeline?.result
      _ = await terminal?.result
      await fixture.close()
      throw error
    }
    await fixture.close()
  }
}

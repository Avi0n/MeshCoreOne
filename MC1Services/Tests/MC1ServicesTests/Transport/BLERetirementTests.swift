@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("Retirement contract BLE admission")
@MainActor
struct BLERetirementTests {
  @Test
  func `partially submitted pipeline drains its accepted reply after revoking the next unsent frame`() async throws {
    let harness = await BLESetupRetirementHarness.make()
    let binding = SessionTransportBinding()
    let view = try await harness.connect(binding: binding)
    let session = MeshCoreSession(
      transport: view,
      configuration: SessionConfiguration(channelPipelineIdleTimeout: 10, channelPipelineHardTimeout: 10)
    )
    let resource = ConnectionResource(binding: binding, transport: harness.adapter, session: session, wifi: nil, ble: harness.adapter)
    var pipeline: Task<(received: [ChannelInfo], missing: [UInt8]), Error>?
    var retirement: Task<Void, Never>?
    do {
      try await harness.start(session)
      let peripheral = harness.peripheral
      harness.native.setWriteHandler { data in
        if data.first == CommandCode.getChannel.rawValue {
          harness.native.isReadyToWrite = false
        }
      }
      pipeline = Task { try await session.getChannels(indices: [0, 1]) }
      try await harness.awaitCondition { await harness.machine.isAwaitingWriteWithoutResponseReady }
      #expect(harness.native.writePayloads.filter { $0.first == CommandCode.getChannel.rawValue }.count == 1)
      pipeline?.cancel()
      let retired = OSAllocatedUnfairLock(initialState: false)
      retirement = Task {
        await resource.retire(closePhysical: false)
        retired.withLock { $0 = true }
      }
      let unsentReleased = await harness.observedBeforeDeadline {
        await !harness.machine.isAwaitingWriteWithoutResponseReady
      }
      #expect(unsentReleased)
      guard unsentReleased else { throw BLESetupRetirementHarness.WaitError.expired }
      #expect(!retired.withLock { $0 }, "A submitted frame retains the exchange until its reply or original timeout")
      #expect(harness.native.cancellationCount == 0)
      var reply = Data([ResponseCode.channelInfo.rawValue, 0])
      reply.append(Data(repeating: 0, count: 32 + 16))
      await harness.inject(reply)
      try await harness.awaitCondition { retired.withLock { $0 } }
      await retirement?.value
      _ = await pipeline?.result
      #expect(harness.native.writePayloads.filter { $0.first == CommandCode.getChannel.rawValue }.count == 1)
      #expect(await session.subscriberCountForTest == 0)
      harness.native.setWriteHandler(nil)
      harness.native.isReadyToWrite = true
      let nextBinding = SessionTransportBinding()
      let nextView = await harness.adapter.makeSessionTransport(deviceID: peripheral.identifier, binding: nextBinding)
      await harness.adapter.refreshDataStream(sessionBinding: nextBinding)
      let next = MeshCoreSession(transport: nextView)
      try await harness.start(next)
      let probe = Task { try await next.factoryReset() }
      do {
        try await harness.awaitCondition { await harness.machine.setupWriteIsPending }
        await harness.completeWrite()
        await harness.inject(Data([ResponseCode.ok.rawValue]))
        try await probe.value
      } catch {
        await harness.machine.shutdown()
        _ = await probe.result
        await next.stop(disconnectTransport: false)
        throw error
      }
      nextBinding.revoke()
      await next.stop(disconnectTransport: false, drainExchanges: true)
      await next.waitForQuiescence()
      await harness.adapter.disconnect(sessionBinding: nextBinding)
    } catch {
      harness.native.setWriteHandler(nil)
      await harness.machine.shutdown()
      _ = await pipeline?.result
      await retirement?.value
      await session.stop(disconnectTransport: false)
      await session.waitForQuiescence()
      throw error
    }
    await harness.machine.shutdown()
    #expect(await !harness.machine.isRSSIKeepaliveActive)
  }

  @Test(arguments: [false, true])
  func `retirement releases the first unsent pipeline write without a readiness callback`(closePhysical: Bool) async throws {
    let harness = await BLESetupRetirementHarness.make()
    let binding = SessionTransportBinding()
    let view = try await harness.connect(binding: binding)
    let session = MeshCoreSession(transport: view, configuration: SessionConfiguration(defaultTimeout: 10))
    let resource = ConnectionResource(binding: binding, transport: harness.adapter, session: session, wifi: nil, ble: harness.adapter)
    var pipeline: Task<(received: [ChannelInfo], missing: [UInt8]), Error>?
    var retirement: Task<Void, Never>?
    var successor: MeshCoreSession?
    do {
      try await harness.start(session)
      let writesBeforePipeline = harness.native.writePayloads
      harness.native.isReadyToWrite = false
      pipeline = Task { try await session.getChannels(indices: [0, 1]) }
      try await harness.awaitCondition { await harness.machine.isAwaitingWriteWithoutResponseReady }
      #expect(harness.native.writePayloads == writesBeforePipeline)

      let retired = OSAllocatedUnfairLock(initialState: false)
      retirement = Task {
        await resource.retire(closePhysical: closePhysical)
        retired.withLock { $0 = true }
      }
      let completed = await harness.observedBeforeDeadline { retired.withLock { $0 } }
      #expect(completed, "Revocation must release a frame that has never reached the peripheral")
      guard completed else { throw BLESetupRetirementHarness.WaitError.expired }
      await retirement?.value
      _ = await pipeline?.result
      #expect(harness.native.writePayloads == writesBeforePipeline)
      #expect(await !harness.machine.isAwaitingWriteWithoutResponseReady)
      #expect(await session.subscriberCountForTest == 0)
      #expect(harness.native.cancellationCount == (closePhysical ? 1 : 0))

      harness.native.isReadyToWrite = true
      let nextBinding = SessionTransportBinding()
      let nextView: SessionBoundTransport
      if closePhysical {
        nextView = try await harness.connect(binding: nextBinding)
      } else {
        nextView = await harness.adapter.makeSessionTransport(deviceID: harness.peripheral.identifier, binding: nextBinding)
        await harness.adapter.refreshDataStream(sessionBinding: nextBinding)
      }
      let nextSession = MeshCoreSession(transport: nextView, configuration: SessionConfiguration(defaultTimeout: 2))
      successor = nextSession
      try await harness.start(nextSession)
      let probe = Task { try await nextSession.factoryReset() }
      do {
        try await harness.awaitCondition { await harness.machine.setupWriteIsPending }
        await harness.completeWrite()
        await harness.inject(Data([ResponseCode.ok.rawValue]))
        try await probe.value
      } catch {
        await harness.machine.shutdown()
        _ = await probe.result
        throw error
      }
      #expect(nextBinding.isActive)
      #expect(await nextSession.subscriberCountForTest == 0)
      nextBinding.revoke()
      await nextSession.stop(disconnectTransport: false, drainExchanges: true)
      await nextSession.waitForQuiescence()
      await harness.adapter.disconnect(sessionBinding: nextBinding)
    } catch {
      await harness.machine.shutdown()
      _ = await pipeline?.result
      await retirement?.value
      await session.stop(disconnectTransport: false)
      await successor?.stop(disconnectTransport: false)
      await session.waitForQuiescence()
      await successor?.waitForQuiescence()
      throw error
    }
    await harness.machine.shutdown()
    #expect(await !harness.machine.isRSSIKeepaliveActive)
  }
}

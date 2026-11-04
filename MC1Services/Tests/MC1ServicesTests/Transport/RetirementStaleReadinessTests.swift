@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("Retirement stale readiness")
@MainActor
struct RetirementStaleReadinessTests {
  enum ReadinessCallback: CaseIterable, Sendable {
    case stalePeripheral
    case stillBackpressuredCurrentPeripheral
    case stalePeripheralWhileCurrentReady
  }

  @Test(arguments: ReadinessCallback.allCases)
  func `only current ready callback can release bs pipeline`(_ callback: ReadinessCallback) async throws {
    let harness = await BLESetupRetirementHarness.make()
    let peripheral = harness.peripheral
    let binding = SessionTransportBinding()
    let view = try await harness.connect(binding: binding)
    let session = MeshCoreSession(transport: view)
    let seen = OSAllocatedUnfairLock(initialState: Set<UUID>())
    let stale = BLETransportHarness()
    stale.peripheralState = .disconnected
    stale.isReadyToWrite = true
    let oldPeripheral = stale.peripheral
    let callbackPeripheral = callback == .stillBackpressuredCurrentPeripheral ? peripheral : oldPeripheral
    let callbackDeviceID = callbackPeripheral.identifier
    await harness.machine.observeReadinessCallbacksForContract { id in
      seen.withLock { _ = $0.insert(id) }
    }
    let delegate = await harness.machine.delegateHandler
    delegate.stateMachine = harness.machine
    var pipeline: Task<(received: [ChannelInfo], missing: [UInt8]), Error>?
    do {
      try await harness.start(session)
      let before = harness.native.writePayloads
      harness.native.isReadyToWrite = false
      pipeline = Task { try await session.getChannels(indices: [0]) }
      try await harness.awaitCondition { await harness.machine.isAwaitingWriteWithoutResponseReady }
      if callback == .stalePeripheralWhileCurrentReady {
        harness.native.isReadyToWrite = true
      }
      delegate.peripheralIsReady(toSendWriteWithoutResponse: callbackPeripheral)
      try await harness.awaitCondition { seen.withLock { $0.contains(callbackDeviceID) } }
      #expect(await harness.machine.isAwaitingWriteWithoutResponseReady)
      #expect(harness.native.writePayloads == before)
      #expect(binding.isActive)

      harness.native.isReadyToWrite = true
      delegate.peripheralIsReady(toSendWriteWithoutResponse: peripheral)
      try await harness.awaitCondition {
        harness.native.writePayloads.count == before.count + 1
      }
      var reply = Data([ResponseCode.channelInfo.rawValue, 0])
      reply.append(Data(repeating: 0, count: 32 + 16))
      await harness.inject(reply)
      let result = try await #require(pipeline).value
      #expect(result.received.map(\.index) == [0])
      let probe = Task { try await session.factoryReset() }
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
    } catch {
      await harness.machine.shutdown()
      _ = await pipeline?.result
      await session.stop(disconnectTransport: false)
      await session.waitForQuiescence()
      throw error
    }
    binding.revoke()
    await session.stop(disconnectTransport: false, drainExchanges: true)
    await session.waitForQuiescence()
    await harness.adapter.disconnect(sessionBinding: binding)
    await harness.machine.shutdown()
    #expect(await session.subscriberCountForTest == 0)
  }

  @Test
  func `a retired resource cannot release bs readiness waiter`() async throws {
    let harness = await BLESetupRetirementHarness.make()
    let peripheral = harness.peripheral
    let oldBinding = SessionTransportBinding()
    let oldView = try await harness.connect(binding: oldBinding)
    let oldSession = MeshCoreSession(transport: oldView)
    let oldResource = ConnectionResource(
      binding: oldBinding, transport: harness.adapter, session: oldSession,
      wifi: nil, ble: harness.adapter
    )
    var current: MeshCoreSession?
    var pipeline: Task<(received: [ChannelInfo], missing: [UInt8]), Error>?
    do {
      try await harness.start(oldSession)
      await oldResource.retire(closePhysical: false)
      let currentBinding = SessionTransportBinding()
      let currentView = await harness.adapter.makeSessionTransport(
        deviceID: peripheral.identifier, binding: currentBinding
      )
      await harness.adapter.refreshDataStream(sessionBinding: currentBinding)
      let session = MeshCoreSession(transport: currentView)
      current = session
      try await harness.start(session)
      let before = harness.native.writePayloads
      harness.native.isReadyToWrite = false
      pipeline = Task { try await session.getChannels(indices: [0]) }
      try await harness.awaitCondition { await harness.machine.isAwaitingWriteWithoutResponseReady }
      await oldResource.retire(closePhysical: true)
      #expect(await harness.machine.isAwaitingWriteWithoutResponseReady)
      #expect(harness.native.writePayloads == before)
      #expect(currentBinding.isActive)
      #expect(harness.native.cancellationCount == 0)

      harness.native.isReadyToWrite = true
      let delegate = await harness.machine.delegateHandler
      delegate.stateMachine = harness.machine
      delegate.peripheralIsReady(toSendWriteWithoutResponse: peripheral)
      try await harness.awaitCondition { harness.native.writePayloads.count == before.count + 1 }
      var reply = Data([ResponseCode.channelInfo.rawValue, 0])
      reply.append(Data(repeating: 0, count: 32 + 16))
      await harness.inject(reply)
      #expect(try await #require(pipeline).value.received.map(\.index) == [0])
      let probe = Task { try await session.factoryReset() }
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
      currentBinding.revoke()
      await session.stop(disconnectTransport: false, drainExchanges: true)
      await session.waitForQuiescence()
      await harness.adapter.disconnect(sessionBinding: currentBinding)
    } catch {
      await harness.machine.shutdown()
      _ = await pipeline?.result
      await oldSession.stop(disconnectTransport: false)
      await current?.stop(disconnectTransport: false)
      await oldSession.waitForQuiescence()
      await current?.waitForQuiescence()
      throw error
    }
    await harness.machine.shutdown()
  }
}

private extension BLEStateMachine {
  func observeReadinessCallbacksForContract(_ observer: @escaping @Sendable (UUID) -> Void) {
    testWriteReadinessHandled = observer
  }
}

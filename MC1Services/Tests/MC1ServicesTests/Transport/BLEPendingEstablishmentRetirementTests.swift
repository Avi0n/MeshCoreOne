@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

@Suite("BLE pending establishment retirement")
@MainActor
struct BLEPendingEstablishmentRetirementTests {
  @Test(arguments: [false, true])
  func `owned close cancels cached dial and discovery before joining setup`(discoveryStarted: Bool) async throws {
    let harness = await BLESetupRetirementHarness.make()
    let binding = SessionTransportBinding()
    let view = await harness.adapter.makeSessionTransport(deviceID: harness.peripheral.identifier, binding: binding)
    let session = MeshCoreSession(transport: view)
    let resource = ConnectionResource(binding: binding, transport: harness.adapter, session: session, wifi: nil, ble: harness.adapter)
    let connectExited = OSAllocatedUnfairLock(initialState: false)
    let connecting = Task {
      defer { connectExited.withLock { $0 = true } }
      do { try await view.connect(); return false } catch { return true }
    }
    var retirement: Task<Void, Never>?
    do {
      try await harness.awaitCondition { await harness.machine.currentPhase.name == "connecting" }
      #expect(harness.native.connectCount == 1)
      if discoveryStarted {
        await harness.beginDiscovery()
        #expect(await harness.machine.currentPhase.name == "discoveringServices")
      }
      let retired = OSAllocatedUnfairLock(initialState: false)
      retirement = Task {
        await resource.retire(closePhysical: true)
        retired.withLock { $0 = true }
      }
      try await harness.awaitCondition { !binding.isActive }
      let closedPromptly = await harness.observedBeforeDeadline {
        retired.withLock { $0 } && connectExited.withLock { $0 }
      }
      #expect(closedPromptly, "Owned retirement must cancel setup without a callback or setup watchdog")
      if !closedPromptly { throw BLESetupRetirementHarness.WaitError.expired }
      #expect(await connecting.value)
      await retirement?.value
      #expect(harness.native.cancellationCount == 1)
      #expect(await harness.machine.currentDiscoveryTimeoutExtensions == 0)
      #expect(await harness.machine.currentPhase.name == "idle")

      let replacementBinding = SessionTransportBinding()
      let replacement = await harness.adapter.makeSessionTransport(deviceID: harness.peripheral.identifier, binding: replacementBinding)
      let replacementConnect = Task { try await replacement.connect() }
      do {
        try await harness.awaitCondition { await harness.machine.currentPhase.name == "connecting" }
        await harness.finishDiscovery()
        try await replacementConnect.value
        try await replacement.sendWithoutResponse(Data([0xA2]))
        #expect(harness.native.connectCount == 2)
        #expect(harness.native.writePayloads == [Data([0xA2])])
        await resource.retire(closePhysical: true)
        #expect(await harness.machine.isConnected)
        #expect(replacementBinding.isActive)
        replacementBinding.revoke()
        await harness.adapter.disconnect(sessionBinding: replacementBinding)
      } catch {
        await harness.machine.shutdown()
        _ = await replacementConnect.result
        throw error
      }
    } catch {
      // Failure cleanup happens only after the bounded setup-cancellation assertion.
      await harness.machine.shutdown()
      _ = await connecting.value
      await retirement?.value
      await session.stop(disconnectTransport: false)
      await session.waitForQuiescence()
      throw error
    }
    await harness.machine.shutdown()
    #expect(await !harness.machine.isRSSIKeepaliveActive)
    #expect(await session.subscriberCountForTest == 0)
  }

  @Test(arguments: [false, true])
  func `established retirement drains actual hardware completion and command reply`(replyBeforeCompletion: Bool) async throws {
    let harness = await BLESetupRetirementHarness.make()
    let binding = SessionTransportBinding()
    let view = try await harness.connect(binding: binding)
    let session = MeshCoreSession(transport: view, configuration: SessionConfiguration(defaultTimeout: 2))
    let resource = ConnectionResource(binding: binding, transport: harness.adapter, session: session, wifi: nil, ble: harness.adapter)
    var caller: Task<Void, Error>?
    var retirement: Task<Void, Never>?
    do {
      try await harness.start(session)
      let request = Task { try await session.factoryReset() }
      caller = request
      try await harness.awaitCondition { await harness.machine.setupWriteIsPending }
      request.cancel()
      await #expect(throws: CancellationError.self) { try await request.value }
      let retired = OSAllocatedUnfairLock(initialState: false)
      retirement = Task {
        await resource.retire(closePhysical: true)
        retired.withLock { $0 = true }
      }
      try await harness.awaitCondition { await !session.sessionIsRunning }
      #expect(!retired.withLock { $0 })
      #expect(harness.native.cancellationCount == 0)
      if replyBeforeCompletion { await harness.inject(Data([ResponseCode.ok.rawValue])) }
      await harness.completeWrite()
      await binding.waitForQuiescence()
      if !replyBeforeCompletion {
        #expect(!retired.withLock { $0 })
        #expect(harness.native.cancellationCount == 0)
        await harness.inject(Data([ResponseCode.ok.rawValue]))
      }
      try await harness.awaitCondition { retired.withLock { $0 } }
      await retirement?.value
      #expect(harness.native.cancellationCount == 1)
      #expect(await session.subscriberCountForTest == 0)
    } catch {
      await harness.completeWrite()
      await harness.inject(Data([ResponseCode.ok.rawValue]))
      await harness.machine.shutdown()
      _ = await caller?.result
      await retirement?.value
      await session.stop(disconnectTransport: false)
      await session.waitForQuiescence()
      throw error
    }
    await harness.machine.shutdown()
  }

  @Test
  func `stale adapter and actor cancellation cannot sever a successor dial`() async throws {
    let harness = await BLESetupRetirementHarness.make()
    let old = SessionTransportBinding()
    _ = await harness.adapter.makeSessionTransport(deviceID: harness.peripheral.identifier, binding: old)
    let current = SessionTransportBinding()
    let view = await harness.adapter.makeSessionTransport(deviceID: harness.peripheral.identifier, binding: current)
    let connecting = Task { try await view.connect() }
    do {
      try await harness.awaitCondition { await harness.machine.currentPhase.name == "connecting" }
      #expect(!old.isActive)
      let generation = await harness.machine.currentConnectionGeneration
      await harness.adapter.cancelPendingEstablishment(sessionBinding: old)
      await harness.machine.cancelPendingEstablishment(sessionBinding: old)
      await harness.machine.disconnect(sessionBinding: old)
      #expect(await harness.machine.currentPhase.name == "connecting")
      #expect(await harness.machine.currentConnectionGeneration == generation)
      #expect(harness.native.cancellationCount == 0)
      await harness.finishDiscovery()
      try await connecting.value
      try await view.sendWithoutResponse(Data([0xA2]))
      #expect(harness.native.writePayloads == [Data([0xA2])])
      current.revoke()
      await harness.adapter.disconnect(sessionBinding: current)
    } catch {
      await harness.machine.shutdown()
      _ = await connecting.result
      throw error
    }
    await harness.machine.shutdown()
  }

  @Test
  func `preserved established lease renews its stream and rejects a stale resource close`() async throws {
    let harness = await BLESetupRetirementHarness.make()
    let binding = SessionTransportBinding()
    let view = try await harness.connect(binding: binding)
    let session = MeshCoreSession(transport: view, configuration: SessionConfiguration(defaultTimeout: 2))
    let resource = ConnectionResource(binding: binding, transport: harness.adapter, session: session, wifi: nil, ble: harness.adapter)
    var successor: MeshCoreSession?
    do {
      try await harness.start(session)
      await resource.retire(closePhysical: false)
      #expect(harness.native.cancellationCount == 0)
      #expect(await harness.machine.isConnected)
      let replacementBinding = SessionTransportBinding()
      let replacementView = await harness.adapter.makeSessionTransport(deviceID: harness.peripheral.identifier, binding: replacementBinding)
      await harness.adapter.refreshDataStream(sessionBinding: replacementBinding)
      let replacementSession = MeshCoreSession(transport: replacementView, configuration: SessionConfiguration(defaultTimeout: 2))
      successor = replacementSession
      try await harness.start(replacementSession)
      await resource.retire(closePhysical: true)
      #expect(harness.native.cancellationCount == 0)
      #expect(await harness.machine.isConnected)
      let probe = Task { try await replacementSession.factoryReset() }
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
      #expect(harness.native.connectCount == 1)
      replacementBinding.revoke()
      await replacementSession.stop(disconnectTransport: false, drainExchanges: true)
      await replacementSession.waitForQuiescence()
      await harness.adapter.disconnect(sessionBinding: replacementBinding)
    } catch {
      await harness.machine.shutdown()
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

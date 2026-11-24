@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import Testing

@Suite("BLE peripheral native object admission")
struct BLEStateMachinePeripheralEventAdmissionTests {
  @Test
  func `inbound traffic bond refresh rejects replaced native objects with the same Device ID`() async throws {
    let harness = BLETransportHarness()
    let replaced = BLETransportHarness(deviceID: harness.deviceID)
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    let stamp = Date().addingTimeInterval(-60)
    await machine.recordBondVerification(deviceID: harness.deviceID, at: stamp)
    await machine.setAppSessionLive(deviceID: harness.deviceID)
    let bridge = await machine.delegateHandler
    if let rejected = bridge.triggerInboundTrafficBondRefresh(from: replaced.peripheral) {
      await rejected.value
    }
    #expect(await machine.bondVerificationDate(for: harness.deviceID) == stamp)
    let accepted = try #require(bridge.triggerInboundTrafficBondRefresh(from: harness.peripheral))
    await accepted.value
    #expect(try #require(await machine.bondVerificationDate(for: harness.deviceID)) > stamp)
    await machine.shutdown()
  }

  @Test
  func `write readiness rejects replaced native objects with the same Device ID`() async throws {
    let harness = BLETransportHarness(supportsWriteWithoutResponse: true)
    let replaced = BLETransportHarness(deviceID: harness.deviceID)
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    let sender = Task { () -> Bool in
      do { try await machine.sendWithoutResponse(Data([1])); return true } catch { return false }
    }
    try #require(await harness.awaitCondition { await machine.isAwaitingWriteWithoutResponseReady })
    let bridge = await machine.delegateHandler
    harness.isReadyToWrite = true
    if let rejected = bridge.readyForWrite(from: replaced.peripheral) { await rejected.value }
    #expect(await machine.isAwaitingWriteWithoutResponseReady)
    #expect(harness.writePayloads.isEmpty)
    let delivered = try #require(bridge.readyForWrite(from: harness.peripheral))
    await delivered.value
    #expect(await sender.value)
    #expect(harness.writePayloads == [Data([1])])
    await machine.shutdown()
  }

  @Test
  func `inbound traffic bond refresh rejects an admitted callback from a prior physical generation`() async throws {
    let harness = BLETransportHarness()
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    let oldGeneration = await machine.currentConnectionGeneration
    try await reconnect(machine, harness: harness)
    let stamp = Date().addingTimeInterval(-60)
    await machine.recordBondVerification(deviceID: harness.deviceID, at: stamp)
    await machine.setAppSessionLive(deviceID: harness.deviceID)
    await machine.handleLiveTrafficBondRefresh(harness.peripheral, generation: oldGeneration)
    #expect(await machine.bondVerificationDate(for: harness.deviceID) == stamp)
    await machine.shutdown()
  }

  @Test
  func `write readiness rejects an admitted callback from a prior physical generation`() async throws {
    let harness = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    let oldGeneration = await machine.currentConnectionGeneration
    try await reconnect(machine, harness: harness)
    let sender = Task { () -> Bool in
      do { try await machine.sendWithoutResponse(Data([1])); return true } catch { return false }
    }
    try #require(await harness.awaitCondition { await machine.isAwaitingWriteWithoutResponseReady })
    harness.isReadyToWrite = true
    await machine.handlePeripheralReadyForWriteWithoutResponse(harness.peripheral, generation: oldGeneration)
    #expect(await machine.isAwaitingWriteWithoutResponseReady)
    #expect(harness.writePayloads.isEmpty)
    let bridge = await machine.delegateHandler
    let delivered = try #require(bridge.readyForWrite(from: harness.peripheral))
    await delivered.value
    #expect(await sender.value)
    #expect(harness.writePayloads == [Data([1])])
    await machine.shutdown()
  }

  private func reconnect(_ machine: BLEStateMachine, harness: BLETransportHarness) async throws {
    let bridge = await machine.delegateHandler
    bridge.centralManager(
      harness.central, didDisconnectPeripheral: harness.peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(), isReconnecting: true, error: nil
    )
    try #require(await harness.awaitCondition { await machine.currentPhase.kind == .autoReconnecting })
    bridge.centralManager(harness.central, didConnect: harness.peripheral)
    try #require(await harness.awaitCondition { harness.serviceDiscoveryCount == 2 })
    bridge.peripheral(harness.peripheral, didDiscoverServices: nil)
    try #require(await harness.awaitCondition { harness.characteristicDiscoveryCount == 2 })
    bridge.peripheral(harness.peripheral, didDiscoverCharacteristicsFor: harness.service, error: nil)
    try #require(await harness.awaitCondition { harness.subscriptionCount == 2 })
    bridge.peripheral(harness.peripheral, didUpdateNotificationStateFor: harness.rx, error: nil)
    try #require(await harness.awaitCondition { await machine.isConnected })
  }
}

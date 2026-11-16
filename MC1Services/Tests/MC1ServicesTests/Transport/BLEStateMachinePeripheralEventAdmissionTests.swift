@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import Testing

@Suite("BLE peripheral native object admission")
struct BLEStateMachinePeripheralEventAdmissionTests {
  @Test
  func `RSSI rejects replaced native objects with the same Device ID`() async throws {
    let harness = BLETransportHarness()
    let replaced = BLETransportHarness(deviceID: harness.deviceID)
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    let stamp = Date().addingTimeInterval(-60)
    await machine.recordBondVerification(deviceID: harness.deviceID, at: stamp)
    await machine.setAppSessionLive(deviceID: harness.deviceID)
    let bridge = await machine.delegateHandler
    let failure = NSError(domain: CBErrorDomain, code: CBError.connectionFailed.rawValue)
    if let rejected = bridge.readRSSI(from: replaced.peripheral, RSSI: -50, error: failure) {
      await rejected.value
    }
    #expect(await machine.consecutiveRSSIFailures == 0)
    if let rejected = bridge.readRSSI(from: replaced.peripheral, RSSI: -50, error: nil) {
      await rejected.value
    }
    #expect(await machine.bondVerificationDate(for: harness.deviceID) == stamp)
    let failed = try #require(bridge.readRSSI(from: harness.peripheral, RSSI: -50, error: failure))
    await failed.value
    #expect(await machine.consecutiveRSSIFailures == 1)
    let succeeded = try #require(bridge.readRSSI(from: harness.peripheral, RSSI: -50, error: nil))
    await succeeded.value
    #expect(await machine.consecutiveRSSIFailures == 0)
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
  func `RSSI rejects an admitted callback from a prior physical generation`() async throws {
    let harness = BLETransportHarness()
    let machine = BLEStateMachine(centralManager: harness.central)
    _ = try await harness.connect(machine)
    let oldGeneration = await machine.currentConnectionGeneration
    try await reconnect(machine, harness: harness)
    let stamp = Date().addingTimeInterval(-60)
    await machine.recordBondVerification(deviceID: harness.deviceID, at: stamp)
    await machine.setAppSessionLive(deviceID: harness.deviceID)
    let failure = NSError(domain: CBErrorDomain, code: CBError.connectionFailed.rawValue)
    await machine.handleDidReadRSSI(harness.peripheral, RSSI: -50, error: nil, generation: oldGeneration)
    await machine.handleDidReadRSSI(harness.peripheral, RSSI: -50, error: failure, generation: oldGeneration)
    #expect(await machine.consecutiveRSSIFailures == 0)
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

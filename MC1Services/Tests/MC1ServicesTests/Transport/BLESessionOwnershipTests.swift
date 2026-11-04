@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import os
import Testing

@Suite("BLE session physical ownership")
@MainActor
struct BLESessionOwnershipTests {
  @Test
  func `revocation after a write slot wait cannot submit over the same physical lease`() async throws {
    let native = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: native.central)
    let peripheral = native.peripheral
    await machine.primeOwnershipConnection(peripheral)
    let old = SessionTransportBinding()
    let submitted = Task { try await machine.send(Data([1]), sessionBinding: old) }
    try await waitUntil { await machine.ownershipWriteIsPending }
    let queued = Task { try await machine.send(Data([2]), sessionBinding: old) }
    try await waitUntil { await machine.queuedWriteCount == 1 }

    old.revoke()
    await machine.completeOwnershipWrite()
    try await submitted.value
    await #expect(throws: CancellationError.self) { try await queued.value }
    #expect(native.writePayloads == [Data([1])])

    let replacement = Task {
      try await machine.send(Data([3]), sessionBinding: SessionTransportBinding())
    }
    try await waitUntil { await machine.ownershipWriteIsPending }
    await machine.completeOwnershipWrite()
    try await replacement.value
    #expect(native.writePayloads == [Data([1]), Data([3])])
    await machine.shutdown()
  }

  @Test
  func `revocation during Write Command backpressure prevents the physical write`() async throws {
    let native = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: native.central)
    let peripheral = native.peripheral
    await machine.primeOwnershipConnection(peripheral)
    let old = SessionTransportBinding()
    let queued = Task { try await machine.sendWithoutResponse(Data([1]), sessionBinding: old) }
    try await waitUntil { await machine.isAwaitingWriteWithoutResponseReady }

    old.revoke()
    native.isReadyToWrite = true
    await machine.handlePeripheralReadyForWriteWithoutResponse(peripheral)
    await #expect(throws: CancellationError.self) { try await queued.value }
    #expect(native.writePayloads.isEmpty)

    native.isReadyToWrite = true
    try await machine.sendWithoutResponse(Data([2]), sessionBinding: SessionTransportBinding())
    #expect(native.writePayloads == [Data([2])])
    await machine.shutdown()
  }

  @Test
  func `an older disconnect cannot settle a newer physical generation to idle`() async throws {
    let native = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: native.central)
    let peripheral = native.peripheral
    let central = native.central
    await machine.primeOwnershipConnection(peripheral, central: central)
    let closing = Task { await machine.disconnect(expectedGeneration: 0) }
    try await waitUntil { await machine.currentPhase.name == "disconnecting" }

    await machine.advanceConnectionGeneration()
    await machine.primeOwnershipConnection(peripheral)
    await closing.value
    #expect(await machine.currentPhase.name == "connected")
    #expect(await machine.currentConnectionGeneration == 1)
    native.isReadyToWrite = true
    try await machine.sendWithoutResponse(Data([3]), sessionBinding: SessionTransportBinding())
    #expect(native.writePayloads == [Data([3])])
    await machine.shutdown()
  }

  @Test
  func `stale session binding cannot renew a stream or reset a replacement live flag`() async {
    let native = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: native.central)
    let peripheral = native.peripheral
    await machine.primeOwnershipConnection(peripheral)
    let old = SessionTransportBinding()
    let replacement = SessionTransportBinding()
    await machine.setSessionBinding(old)
    old.revoke()
    await machine.setSessionBinding(replacement)
    await machine.setAppSessionLive(deviceID: peripheral.identifier, sessionBinding: replacement)
    await machine.setWritePacingDelay(0, sessionBinding: replacement)
    let staleDeviceID = UUID()
    await machine.recordBondVerification(deviceID: peripheral.identifier, at: Date(), sessionBinding: replacement)
    await machine.setSessionBinding(old)
    await machine.setWritePacingDelay(1, sessionBinding: old)
    await machine.recordBondVerification(deviceID: staleDeviceID, at: Date(), sessionBinding: old)
    await machine.setAppSessionLive(deviceID: nil, sessionBinding: old)
    #expect(await machine.isAppSessionLive(deviceID: peripheral.identifier))
    #expect(await machine.writePacingDelay == 0)
    #expect(await machine.hasBondVerification(deviceID: peripheral.identifier))
    #expect(await !machine.hasBondVerification(deviceID: staleDeviceID))
    #expect(await machine.renewDataStream(sessionBinding: old) == nil)
    #expect(await machine.renewDataStream(sessionBinding: replacement) != nil)
    await machine.shutdown()
  }

  @Test
  func `a revoked adapter factory cannot replace the current target or physical binding`() async throws {
    let native = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: native.central)
    let peripheral = native.peripheral
    await machine.primeOwnershipConnection(peripheral)
    let adapter = iOSBLETransport(stateMachine: machine)
    let old = SessionTransportBinding()
    _ = await adapter.makeSessionTransport(deviceID: UUID(), binding: old)
    let replacement = SessionTransportBinding()
    let current = await adapter.makeSessionTransport(deviceID: peripheral.identifier, binding: replacement)

    #expect(!old.isActive)
    let stale = await adapter.makeSessionTransport(deviceID: UUID(), binding: old)
    await #expect(throws: CancellationError.self) { try await stale.connect() }
    #expect(replacement.isActive)
    try await current.connect()
    native.isReadyToWrite = true
    try await current.sendWithoutResponse(Data([3]))
    #expect(native.writePayloads == [Data([3])])
    #expect(await machine.connectedDeviceID == peripheral.identifier)
    await machine.shutdown()
  }

  @Test
  func `accepted physical loss revokes old IO and stamps events before later actor generations`() async {
    let native = BLETransportHarness(supportsWriteWithoutResponse: true)
    let machine = BLEStateMachine(centralManager: native.central)
    let peripheral = native.peripheral
    await machine.primeOwnershipConnection(peripheral)
    let old = SessionTransportBinding()
    await machine.setSessionBinding(old)
    let events = OSAllocatedUnfairLock<[BLELifecycleEvent]>(initialState: [])
    await machine.setLifecycleEventHandler { event, _ in events.withLock { $0.append(event) } }
    let acceptedAt = CFAbsoluteTimeGetCurrent()

    await machine.handleDidDisconnect(peripheral, timestamp: acceptedAt, isReconnecting: false, error: nil)
    await machine.advanceConnectionGeneration()

    #expect(!old.isActive)
    let captured = events.withLock { $0 }
    #expect(captured.count == 2)
    #expect(captured.allSatisfy { $0.origin.generation == 0 && $0.origin.eventTime == acceptedAt })
    #expect(await machine.currentConnectionGeneration == 1)
    await machine.shutdown()
  }
}

private extension BLEStateMachine {
  var ownershipWriteIsPending: Bool {
    pendingWriteContinuation != nil
  }

  func primeOwnershipConnection(_ peripheral: CBPeripheral, central: CBCentralManager? = nil) {
    if let central { centralManager = central }
    let tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable]
    )
    let rx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic),
      properties: [.notify], value: nil, permissions: [.readable]
    )
    let (_, continuation) = AsyncStream.makeStream(of: Data.self)
    phase = .connected(peripheral: peripheral, tx: tx, rx: rx, dataContinuation: continuation)
    captureWriteWithoutResponseCapability(from: tx)
  }

  func completeOwnershipWrite() {
    guard case let .connected(peripheral, tx, _, _) = phase else { return }
    handleDidWriteValue(peripheral, characteristic: tx, error: nil, writeSequence: pendingWriteSequence)
  }
}

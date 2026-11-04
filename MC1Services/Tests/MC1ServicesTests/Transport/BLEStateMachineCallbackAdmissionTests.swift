@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import Testing

@Suite("BLE delegate callback origin admission")
struct BLEStateMachineCallbackAdmissionTests {
  @Test
  func `data from replaced peripheral and characteristic does not enter current stream`() async throws {
    let active = BLETransportHarness()
    let old = BLETransportHarness(deviceID: active.deviceID)
    let machine = BLEStateMachine(centralManager: active.central)
    let stream = try await active.connect(machine)
    let bridge = await machine.delegateHandler
    old.rx.value = Data([1])
    active.tx.value = Data([2])
    active.rx.value = Data([3])

    bridge.peripheral(old.peripheral, didUpdateValueFor: old.rx, error: nil)
    bridge.peripheral(active.peripheral, didUpdateValueFor: active.tx, error: nil)
    bridge.peripheral(active.peripheral, didUpdateValueFor: old.rx, error: nil)
    bridge.peripheral(active.peripheral, didUpdateValueFor: active.rx, error: nil)
    await machine.shutdown()
    var received: [Data] = []
    for await frame in stream {
      received.append(frame)
    }
    #expect(received == [Data([3])])
  }

  @Test(arguments: [false, true], [false, true])
  func `wrong-origin ACK cannot consume the current write sequence`(oldFails: Bool, wrongCharacteristic: Bool) async throws {
    let active = BLETransportHarness()
    let old = BLETransportHarness(deviceID: active.deviceID)
    let machine = BLEStateMachine(centralManager: active.central)
    _ = try await active.connect(machine)
    let sender = Task { () -> Bool in
      do { try await machine.send(Data([1])); return true } catch { return false }
    }
    try #require(await active.awaitCondition { active.writePayloads.count == 1 })
    let bridge = await machine.delegateHandler
    let failure = NSError(domain: CBErrorDomain, code: CBError.connectionFailed.rawValue)
    bridge.peripheral(
      wrongCharacteristic ? active.peripheral : old.peripheral,
      didWriteValueFor: wrongCharacteristic ? old.tx : active.tx, error: oldFails ? failure : nil
    )
    bridge.peripheral(active.peripheral, didWriteValueFor: active.tx, error: oldFails ? nil : failure)
    #expect(await sender.value == oldFails)
    await machine.shutdown()
  }

  @Test(arguments: [false, true], [false, true])
  func `restoration stands down after normal setup owns the machine`(discovering: Bool, sameRadio: Bool) async throws {
    let active = BLETransportHarness()
    let restored = BLETransportHarness(deviceID: sameRadio ? active.deviceID : UUID())
    let machine = BLEStateMachine(centralManager: active.central)
    let pending = Task { try await machine.connect(to: active.deviceID) }
    try #require(await active.awaitCondition { active.connectCount == 1 })
    let bridge = await machine.delegateHandler
    if discovering {
      bridge.centralManager(active.central, didConnect: active.peripheral)
      try #require(await active.awaitCondition { active.serviceDiscoveryCount == 1 })
    }
    let before = await machine.currentPhase
    let continuation: CheckedContinuation<Void, Error>
    switch before {
    case let .connecting(_, pending, _), let .discoveringServices(_, pending): continuation = pending
    default:
      await machine.shutdown()
      _ = await pending.result
      Issue.record("Expected active production setup")
      return
    }
    let generation = await machine.currentConnectionGeneration
    let delivery = try #require(bridge.restoreState(from: restored.peripheral))
    await delivery.value
    let after = await machine.currentPhase
    #expect(after.kind == before.kind)
    #expect(after.peripheral === active.peripheral)
    #expect(await machine.currentConnectionGeneration == generation)
    #expect(active.cancellationCount == (sameRadio ? 0 : 1))
    // A displaced setup continuation still needs completion during failing-test cleanup.
    if after.kind != before.kind { continuation.resume(throwing: CancellationError()) }
    await machine.shutdown()
    _ = await pending.result
  }
}

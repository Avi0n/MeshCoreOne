import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// A transient `didFailToConnect` during `.autoReconnecting` (the common
/// backgrounded case: `CBError.encryptionTimedOut`) must re-issue the pending
/// connect and stay in `.autoReconnecting` rather than abandon the OS pending
/// connection. Only a definitive auth code, or an exhausted retry budget,
/// tears the episode down and notifies loss.
@Suite("BLEStateMachine auto-reconnect connect retry")
struct BLEStateMachineAutoReconnectRetryTests {
  private var encryptionTimedOut: NSError {
    NSError(domain: CBErrorDomain, code: CBError.encryptionTimedOut.rawValue)
  }

  private var peerRemovedPairing: NSError {
    NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)
  }

  private var genericTransient: NSError {
    NSError(domain: CBErrorDomain, code: CBError.connectionTimeout.rawValue)
  }

  private func makeRecorder(on sm: BLEStateMachine) async -> RetryDisconnectionRecorder {
    let recorder = RetryDisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      recorder.append(deviceID: deviceID, error: error)
    }
    return recorder
  }

  @Test
  func `transient connect failure below cap re-arms and stays auto-reconnecting`() async {
    let sm = BLEStateMachine()
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedRetryPeripheral()
    let recorder = await makeRecorder(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)

    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(await sm.currentAutoReconnectConnectFailures == 1)
  }

  @Test
  func `exhausting the budget with encryption timeouts gives up as an auth failure`() async {
    let sm = BLEStateMachine()
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedRetryPeripheral()
    let recorder = await makeRecorder(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    // Every failure below the cap re-arms silently and stays in the episode.
    for _ in 1..<BLEStateMachine.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)

    // The failure that reaches the cap gives up.
    await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    #expect(recorder.events.first?.deviceID == peripheral.identifier)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `exhausting the budget with generic failures gives up as a connection failure`() async {
    let sm = BLEStateMachine()
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedRetryPeripheral()
    let recorder = await makeRecorder(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    for _ in 1..<BLEStateMachine.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: genericTransient)
    }
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)

    await sm.handleDidFailToConnect(peripheral, error: genericTransient)

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    guard case .connectionFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .connectionFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `a definitive auth code tears down immediately without re-arming`() async {
    let sm = BLEStateMachine()
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedRetryPeripheral()
    let recorder = await makeRecorder(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    await sm.handleDidFailToConnect(peripheral, error: peerRemovedPairing)

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    #expect(recorder.events.first?.deviceID == peripheral.identifier)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
    #expect(await sm.currentAutoReconnectConnectFailures == 0)
  }
}

// MARK: - Test doubles and seams

/// Collects `(deviceID, error)` pairs delivered to `onDisconnection`.
private final class RetryDisconnectionRecorder: @unchecked Sendable {
  private(set) var events: [(deviceID: UUID, error: Error?)] = []

  func append(deviceID: UUID, error: Error?) {
    events.append((deviceID, error))
  }
}

/// Retains mock peripherals for the process lifetime. `CBPeripheral` has no
/// public initializer and its `-dealloc` touches internals that a runtime-
/// allocated instance never set up, so releasing one crashes; never freeing
/// them keeps the doubles usable.
private enum RetryPeripheralStore {
  nonisolated(unsafe) static var retained: [CBPeripheral] = []
}

/// A `CBPeripheral` double with a stable identity and `.disconnected` state.
private final class RetryTestPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .disconnected
  }
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeLeakedRetryPeripheral() -> RetryTestPeripheral {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(RetryTestPeripheral.self, 0) as! RetryTestPeripheral
  RetryPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Actor-isolated seam that installs the `.autoReconnecting` phase the
/// disconnect and restoration paths would produce, then lets the real handler run.
private extension BLEStateMachine {
  func primeAutoReconnecting(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
  }
}

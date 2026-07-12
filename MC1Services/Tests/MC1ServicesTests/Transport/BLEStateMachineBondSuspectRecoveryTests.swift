import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// End-to-end coverage for the two teardown behaviors above the error mapping:
/// a connected-but-wedged auto-reconnect discovery escalating a silent stall to
/// the guided re-pair path, and a disconnect arriving in the `.discoveryComplete`
/// window settling the machine fully instead of forking into auto-reconnect.
@Suite("BLEStateMachine bond-suspect recovery")
struct BLEStateMachineBondSuspectRecoveryTests {
  /// Fires the armed auto-reconnect watchdog well within the test window.
  private let watchdogFiringTimeout: TimeInterval = 0.02

  /// Bounds the wait for the watchdog teardown so a missed escalation fails fast.
  private let observationWindow: TimeInterval = 5

  // MARK: - Wedged auto-reconnect discovery escalation

  /// A peripheral that stays `.connected` through the auto-reconnect discovery
  /// window without ever completing discovery, with the extension budget spent,
  /// is the strongest in-app signal of a silently invalidated bond. The watchdog
  /// tears down and escalates the notified error to `authenticationFailed` so the
  /// disconnection handler routes it into guided re-pair recovery, not a generic
  /// timeout retry that would keep re-trying the dead bond.
  @Test
  func `wedged auto-reconnect discovery escalates a silent stall to an auth failure`() async {
    let sm = BLEStateMachine(autoReconnectDiscoveryTimeout: watchdogFiringTimeout)
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedPeripheral(BondSuspectConnectedPeripheral.self)
    let recorder = BondSuspectDisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      recorder.append(deviceID: deviceID, error: error)
    }

    await sm.primeAutoReconnectTeardown(peripheral: peripheral)

    let notified = await pollUntilNotEmpty(recorder, within: observationWindow)
    #expect(notified, "The wedged connected auto-reconnect watchdog must tear down and notify")
    #expect(await sm.currentPhase.name == "idle")
    // The extension budget stays bounded at its ceiling; teardown never pushes past it.
    #expect(await sm.currentDiscoveryTimeoutExtensions == BLEStateMachine.maxDiscoveryTimeoutExtensions)
    #expect(recorder.events.count == 1)
    #expect(recorder.events.first?.deviceID == peripheral.identifier)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected BLEError.authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  // MARK: - Discovery-complete window disconnect

  /// A disconnect delivered after discovery completed but before `connect()`
  /// adopts the link must settle the machine in `.idle` via full-disconnect
  /// teardown, not fork into `.autoReconnecting` while `connect()` separately
  /// fails on the vanished phase.
  @Test
  func `disconnect in the discovery-complete window settles fully instead of forking`() async {
    let sm = BLEStateMachine()
    await sm.injectTestCentralManager()
    let peripheral = makeLeakedPeripheral(BondSuspectConnectedPeripheral.self)

    let autoReconnectRecorder = BondSuspectAutoReconnectRecorder()
    await sm.setAutoReconnectingHandler { deviceID, reason in
      autoReconnectRecorder.append(deviceID: deviceID, reason: reason)
    }
    let disconnectionRecorder = BondSuspectDisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      disconnectionRecorder.append(deviceID: deviceID, error: error)
    }

    await sm.primeDiscoveryComplete(peripheral: peripheral)
    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: true,
      error: nil
    )

    #expect(await sm.currentPhase.name == "idle")
    #expect(autoReconnectRecorder.events.isEmpty, "Must not fork into auto-reconnect from the discovery-complete window")
    #expect(disconnectionRecorder.events.isEmpty, "Full-disconnect teardown of an unadopted link notifies no session loss")
  }

  // MARK: - Helpers

  private func pollUntilNotEmpty(_ recorder: BondSuspectDisconnectionRecorder, within timeout: TimeInterval) async -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while recorder.events.isEmpty {
      if Date() > deadline { return false }
      try? await Task.sleep(for: .milliseconds(5))
    }
    return true
  }
}

// MARK: - Test doubles and seams

/// Collects `(deviceID, error)` pairs delivered to `onDisconnection`.
private final class BondSuspectDisconnectionRecorder: @unchecked Sendable {
  private(set) var events: [(deviceID: UUID, error: Error?)] = []

  func append(deviceID: UUID, error: Error?) {
    events.append((deviceID, error))
  }
}

/// Collects `(deviceID, reason)` pairs delivered to `onAutoReconnecting`.
private final class BondSuspectAutoReconnectRecorder: @unchecked Sendable {
  private(set) var events: [(deviceID: UUID, reason: String)] = []

  func append(deviceID: UUID, reason: String) {
    events.append((deviceID, reason))
  }
}

/// Retains mock peripherals for the process lifetime. `CBPeripheral` has no
/// public initializer and its `-dealloc` touches internals that a runtime-
/// allocated instance never set up, so releasing one crashes; never freeing
/// them keeps the doubles usable.
private enum BondSuspectPeripheralStore {
  nonisolated(unsafe) static var retained: [CBPeripheral] = []
}

/// A `CBPeripheral` double whose `state` is forced to `.connected`.
private final class BondSuspectConnectedPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .connected
  }
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeLeakedPeripheral<T: CBPeripheral>(_ type: T.Type) -> T {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(type, 0) as! T
  BondSuspectPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Actor-isolated seams that install the exact state the connect and
/// auto-reconnect paths would produce, then let the real handlers run.
private extension BLEStateMachine {
  /// Installs a connected auto-reconnect phase with a spent extension budget and
  /// arms the real auto-reconnect watchdog so its teardown branch fires.
  func primeAutoReconnectTeardown(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
    discoveryTimeoutExtensions = BLEStateMachine.maxDiscoveryTimeoutExtensions
    armAutoReconnectDiscoveryTimeout(for: peripheral, generation: connectionGeneration)
  }

  /// Installs the transient `.discoveryComplete` phase `connect()` occupies
  /// between notification-state resume and adopting the link.
  func primeDiscoveryComplete(peripheral: CBPeripheral) {
    let tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )
    let rx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic),
      properties: [.notify],
      value: nil,
      permissions: [.readable]
    )
    phase = .discoveryComplete(peripheral: peripheral, tx: tx, rx: rx)
    phaseStartTime = Date()
  }
}

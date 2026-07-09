import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// Drives the callback handlers that route disconnection errors through
/// `BLEStateMachine.makeConnectionError` before invoking `onDisconnection`,
/// proving each site surfaces a bond-invalidation CoreBluetooth error as the
/// typed `BLEError.authenticationFailed` and preserves `nil` for a clean
/// disconnect in the `.connected` branch.
@Suite("BLEStateMachine disconnection error mapping")
struct BLEStateMachineDisconnectionMappingTests {
  /// A CoreBluetooth error every mapped site must surface as `.authenticationFailed`.
  private var bondInvalidationError: NSError {
    NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)
  }

  private func makeCharacteristic(_ uuidString: String) -> CBMutableCharacteristic {
    CBMutableCharacteristic(
      type: CBUUID(string: uuidString),
      properties: [.notify],
      value: nil,
      permissions: [.readable]
    )
  }

  /// Installs a recording `onDisconnection` handler and returns the box collecting
  /// every `(deviceID, error)` pair it receives.
  private func recordDisconnections(on sm: BLEStateMachine) async -> DisconnectionRecorder {
    let recorder = DisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      recorder.append(deviceID: deviceID, error: error)
    }
    return recorder
  }

  private func expectSingleAuthFailure(_ recorder: DisconnectionRecorder, deviceID: UUID) {
    #expect(recorder.events.count == 1)
    #expect(recorder.events.first?.deviceID == deviceID)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected BLEError.authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  // MARK: - handleDidFailToConnect (.autoReconnecting)

  @Test
  func `failed auto-reconnect maps a bond-invalidation error to authenticationFailed`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(MappingTestPeripheral.self)
    let recorder = await recordDisconnections(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    await sm.handleDidFailToConnect(peripheral, error: bondInvalidationError)

    expectSingleAuthFailure(recorder, deviceID: peripheral.identifier)
    #expect(await sm.currentPhase.name == "idle")
  }

  // MARK: - handleDidDisconnect full disconnect (.connected / .autoReconnecting)

  @Test
  func `full disconnect while connected maps a bond-invalidation error to authenticationFailed`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(MappingTestPeripheral.self)
    let recorder = await recordDisconnections(on: sm)
    await sm.primeConnected(peripheral: peripheral)

    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: false,
      error: bondInvalidationError
    )

    expectSingleAuthFailure(recorder, deviceID: peripheral.identifier)
  }

  @Test
  func `full disconnect while connected preserves a nil error as nil`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(MappingTestPeripheral.self)
    let recorder = await recordDisconnections(on: sm)
    await sm.primeConnected(peripheral: peripheral)

    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: false,
      error: nil
    )

    #expect(recorder.events.count == 1)
    #expect(recorder.events.first?.deviceID == peripheral.identifier)
    #expect(recorder.events.first?.error == nil)
  }

  @Test
  func `full disconnect while auto-reconnecting maps a bond-invalidation error to authenticationFailed`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(MappingTestPeripheral.self)
    let recorder = await recordDisconnections(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: false,
      error: bondInvalidationError
    )

    expectSingleAuthFailure(recorder, deviceID: peripheral.identifier)
  }

  // MARK: - Auto-reconnect discovery failures

  @Test
  func `auto-reconnect service discovery failure maps to authenticationFailed`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(MappingTestPeripheral.self)
    let recorder = await recordDisconnections(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    await sm.handleDidDiscoverServices(peripheral, error: bondInvalidationError)

    expectSingleAuthFailure(recorder, deviceID: peripheral.identifier)
    #expect(await sm.currentPhase.name == "idle")
  }

  @Test
  func `auto-reconnect characteristic discovery failure maps to authenticationFailed`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(MappingTestPeripheral.self)
    let recorder = await recordDisconnections(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    let service = CBMutableService(type: CBUUID(string: BLEServiceUUID.nordicUART), primary: true)
    await sm.handleDidDiscoverCharacteristics(peripheral, service: service, error: bondInvalidationError)

    expectSingleAuthFailure(recorder, deviceID: peripheral.identifier)
    #expect(await sm.currentPhase.name == "idle")
  }

  @Test
  func `auto-reconnect notification subscription failure maps to authenticationFailed`() async {
    let sm = BLEStateMachine()
    let peripheral = makeLeakedPeripheral(MappingTestPeripheral.self)
    let recorder = await recordDisconnections(on: sm)
    await sm.primeAutoReconnecting(peripheral: peripheral)

    let rx = makeCharacteristic(BLEServiceUUID.rxCharacteristic)
    await sm.handleDidUpdateNotificationState(peripheral, characteristic: rx, error: bondInvalidationError)

    expectSingleAuthFailure(recorder, deviceID: peripheral.identifier)
    #expect(await sm.currentPhase.name == "idle")
  }
}

// MARK: - Test doubles and seams

/// Collects `(deviceID, error)` pairs delivered to `onDisconnection`.
/// The handler is `@Sendable`; the state machine invokes it from its own
/// isolation, and each test awaits the driving call before reading `events`.
private final class DisconnectionRecorder: @unchecked Sendable {
  private(set) var events: [(deviceID: UUID, error: Error?)] = []

  func append(deviceID: UUID, error: Error?) {
    events.append((deviceID, error))
  }
}

/// Retains mock peripherals for the process lifetime. `CBPeripheral` has no
/// public initializer and its `-dealloc` touches internals that a runtime-
/// allocated instance never set up, so releasing one crashes; never freeing
/// them keeps the doubles usable.
private enum MappingPeripheralStore {
  nonisolated(unsafe) static var retained: [CBPeripheral] = []
}

/// A `CBPeripheral` double with a stable identity and `.disconnected` state.
private final class MappingTestPeripheral: CBPeripheral, @unchecked Sendable {
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
private func makeLeakedPeripheral<T: CBPeripheral>(_ type: T.Type) -> T {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(type, 0) as! T
  MappingPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Actor-isolated seams that install the phases the disconnection handlers
/// switch on, then let tests drive the real handlers.
private extension BLEStateMachine {
  func primeAutoReconnecting(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
  }

  func primeConnected(peripheral: CBPeripheral) {
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
    let (_, continuation) = AsyncStream.makeStream(of: Data.self)
    phase = .connected(peripheral: peripheral, tx: tx, rx: rx, dataContinuation: continuation)
    phaseStartTime = Date()
  }
}

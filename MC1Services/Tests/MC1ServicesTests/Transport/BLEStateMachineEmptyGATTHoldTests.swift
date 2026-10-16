import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// An empty or partial discovery result with a nil error during `.autoReconnecting`
/// must hold the phase rather than drop to `.idle`: surrendering the phase gets the
/// next iOS-delivered `didConnect` cancelled as unexpected, which destroys iOS's
/// standing auto-reconnect registration. These tests replay that incident sequence
/// and pin the invariants around it: error branches still tear down, and explicit
/// disconnect still cleans up fully.
@Suite("BLEStateMachine empty-GATT hold", .serialized)
struct BLEStateMachineEmptyGATTHoldTests {
  private var code6Timeout: NSError {
    NSError(domain: CBErrorDomain, code: CBError.connectionTimeout.rawValue)
  }

  private func makeMachine() async -> (BLEStateMachine, HoldTestCentralManager, HoldTestPeripheral, HoldDisconnectionRecorder) {
    HoldTestPeripheral.reset()
    let sm = BLEStateMachine()
    let central = HoldTestCentralManager(delegate: nil, queue: nil)
    await sm.injectCentralManager(central)
    let peripheral = makeLeakedHoldPeripheral()
    let recorder = HoldDisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      recorder.append(deviceID: deviceID, error: error)
    }
    await sm.primeAutoReconnectingPhase(peripheral: peripheral)
    return (sm, central, peripheral, recorder)
  }

  private func nordicService(characteristics: [CBCharacteristic]?) -> CBMutableService {
    let service = CBMutableService(type: CBUUID(string: BLEServiceUUID.nordicUART), primary: true)
    service.characteristics = characteristics
    return service
  }

  // MARK: - Incident replay

  @Test
  func `empty service discovery holds auto-reconnect and the next didConnect is adopted`() async {
    let (sm, central, peripheral, recorder) = await makeMachine()

    // Empty GATT, nil error: hold the phase, notify nothing, cancel nothing.
    HoldTestPeripheral.storedServices = []
    await sm.handleDidDiscoverServices(peripheral, error: nil)
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(central.cancelledPeripherals.isEmpty)

    // The queued stale disconnect (code=6, isReconnecting) routed through the
    // auto-reconnect handler must leave the machine in `.autoReconnecting`.
    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent(),
      isReconnecting: true,
      error: code6Timeout
    )
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)

    // The subsequent didConnect is adopted: services discovery re-issued, no cancel.
    await sm.handleDidConnect(peripheral)
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(HoldTestPeripheral.discoverServicesCallCount == 1)
    #expect(central.cancelledPeripherals.isEmpty)
    #expect(recorder.events.isEmpty)

    await sm.cancelAutoReconnectTimeoutForTesting()
  }

  @Test
  func `a stale disconnect dropped by the timestamp fence still ends in adoption`() async {
    let (sm, central, peripheral, recorder) = await makeMachine()

    HoldTestPeripheral.storedServices = []
    await sm.handleDidDiscoverServices(peripheral, error: nil)
    #expect(await sm.currentPhase.name == "autoReconnecting")

    // A disconnect stamped before the generation start is dropped by the fence.
    await sm.handleDidDisconnect(
      peripheral,
      timestamp: CFAbsoluteTimeGetCurrent() - 3600,
      isReconnecting: true,
      error: code6Timeout
    )
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)

    await sm.handleDidConnect(peripheral)
    #expect(HoldTestPeripheral.discoverServicesCallCount == 1)
    #expect(central.cancelledPeripherals.isEmpty)

    await sm.cancelAutoReconnectTimeoutForTesting()
  }

  @Test
  func `missing characteristics with nil error holds auto-reconnect and didConnect is adopted`() async {
    let (sm, central, peripheral, recorder) = await makeMachine()

    // Service present but tx/rx absent, nil error: hold.
    await sm.handleDidDiscoverCharacteristics(peripheral, service: nordicService(characteristics: []), error: nil)
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(central.cancelledPeripherals.isEmpty)

    await sm.handleDidConnect(peripheral)
    #expect(HoldTestPeripheral.discoverServicesCallCount == 1)
    #expect(central.cancelledPeripherals.isEmpty)

    await sm.cancelAutoReconnectTimeoutForTesting()
  }

  @Test
  func `notification subscription not notifying with nil error holds auto-reconnect`() async {
    let (sm, central, peripheral, recorder) = await makeMachine()

    let rx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic),
      properties: [.notify],
      value: nil,
      permissions: [.readable]
    )
    // isNotifying is false on a mutable characteristic: the not-notifying branch.
    await sm.handleDidUpdateNotificationState(peripheral, characteristic: rx, error: nil)
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(central.cancelledPeripherals.isEmpty)

    await sm.handleDidConnect(peripheral)
    #expect(HoldTestPeripheral.discoverServicesCallCount == 1)
    #expect(central.cancelledPeripherals.isEmpty)

    await sm.cancelAutoReconnectTimeoutForTesting()
  }

  @Test
  func `notifying subscription with tx-rx missing from phase holds auto-reconnect`() async {
    let (sm, central, peripheral, recorder) = await makeMachine()

    let rx = NotifyingHoldCharacteristic.makeLeaked()
    // Phase was primed with tx/rx nil, so the missing-tx/rx branch fires.
    await sm.handleDidUpdateNotificationState(peripheral, characteristic: rx, error: nil)
    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(central.cancelledPeripherals.isEmpty)

    await sm.cancelAutoReconnectTimeoutForTesting()
  }

  // MARK: - Invariants that must not regress

  @Test
  func `service discovery with a CB error still tears down through the error mapping`() async {
    let (sm, central, peripheral, recorder) = await makeMachine()
    _ = central

    HoldTestPeripheral.storedServices = []
    let attError = NSError(domain: CBATTErrorDomain, code: CBATTError.insufficientEncryption.rawValue)
    await sm.handleDidDiscoverServices(peripheral, error: attError)

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `characteristic discovery with a CB error still tears down through the error mapping`() async {
    let (sm, _, peripheral, recorder) = await makeMachine()

    let genericError = NSError(domain: CBErrorDomain, code: CBError.connectionTimeout.rawValue)
    await sm.handleDidDiscoverCharacteristics(peripheral, service: nordicService(characteristics: nil), error: genericError)

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    guard case .connectionFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .connectionFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `explicit disconnect during auto-reconnect still cleans up fully without notifying loss`() async {
    let (sm, central, peripheral, recorder) = await makeMachine()

    await sm.disconnect()

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.isEmpty, "Explicit disconnect must never fire onDisconnection")
    #expect(central.cancelledPeripherals.contains(peripheral.identifier),
            "Explicit disconnect must cancel the standing auto-reconnect")
  }
}

// MARK: - Test doubles and seams

/// Collects `(deviceID, error)` pairs delivered to `onDisconnection`.
private final class HoldDisconnectionRecorder: @unchecked Sendable {
  private(set) var events: [(deviceID: UUID, error: Error?)] = []

  func append(deviceID: UUID, error: Error?) {
    events.append((deviceID, error))
  }
}

/// A central-manager double that records connection cancellations and swallows
/// connect calls so fake peripherals never reach CoreBluetooth.
private final class HoldTestCentralManager: CBCentralManager, @unchecked Sendable {
  private(set) nonisolated(unsafe) var cancelledPeripherals: [UUID] = []

  override func connect(_ peripheral: CBPeripheral, options: [String: Any]? = nil) {}

  override func cancelPeripheralConnection(_ peripheral: CBPeripheral) {
    cancelledPeripherals.append(peripheral.identifier)
  }
}

/// Retains mock peripherals for the process lifetime. `CBPeripheral` has no
/// public initializer and its `-dealloc` touches internals that a runtime-
/// allocated instance never set up, so releasing one crashes; never freeing
/// them keeps the doubles usable.
private enum HoldPeripheralStore {
  nonisolated(unsafe) static var retained: [AnyObject] = []
}

/// A `CBPeripheral` double created via `class_createInstance`, so it must not
/// declare stored instance properties; per-test state lives in statics and the
/// suite is serialized.
private final class HoldTestPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  nonisolated(unsafe) static var storedServices: [CBService]?
  nonisolated(unsafe) static var discoverServicesCallCount = 0

  static func reset() {
    storedServices = nil
    discoverServicesCallCount = 0
  }

  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .connected
  }

  override var services: [CBService]? {
    Self.storedServices
  }

  override var delegate: CBPeripheralDelegate? {
    get { nil }
    set {}
  }

  override func discoverServices(_ serviceUUIDs: [CBUUID]?) {
    Self.discoverServicesCallCount += 1
  }

  override func discoverCharacteristics(_ characteristicUUIDs: [CBUUID]?, for service: CBService) {}

  override func setNotifyValue(_ enabled: Bool, for characteristic: CBCharacteristic) {}
}

/// A characteristic double reporting the RX UUID with `isNotifying == true`,
/// for the branch where the subscription succeeded but the phase lost tx/rx.
private final class NotifyingHoldCharacteristic: CBCharacteristic, @unchecked Sendable {
  override var uuid: CBUUID {
    CBUUID(string: BLEServiceUUID.rxCharacteristic)
  }

  override var isNotifying: Bool {
    true
  }

  static func makeLeaked() -> NotifyingHoldCharacteristic {
    // swiftlint:disable:next force_cast
    let characteristic = class_createInstance(NotifyingHoldCharacteristic.self, 0) as! NotifyingHoldCharacteristic
    HoldPeripheralStore.retained.append(characteristic)
    return characteristic
  }
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeLeakedHoldPeripheral() -> HoldTestPeripheral {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(HoldTestPeripheral.self, 0) as! HoldTestPeripheral
  HoldPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Actor-isolated seams that install the `.autoReconnecting` phase the
/// disconnect path would produce, then let the real handlers run.
private extension BLEStateMachine {
  func injectCentralManager(_ manager: CBCentralManager) {
    centralManager = manager
  }

  func primeAutoReconnectingPhase(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
  }

  func cancelAutoReconnectTimeoutForTesting() {
    autoReconnectDiscoveryTimeoutTask?.cancel()
    autoReconnectDiscoveryTimeoutTask = nil
  }
}

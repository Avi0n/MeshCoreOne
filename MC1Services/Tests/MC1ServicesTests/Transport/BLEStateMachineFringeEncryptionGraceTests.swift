import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// Exhausting the auto-reconnect budget re-issues the pending connect when
/// the bond verified within `bondVerificationGraceInterval`, or when the
/// app is inactive. A foreground unshielded majority still escalates.
@Suite("BLEStateMachine fringe encryption grace", .serialized)
struct BLEStateMachineFringeEncryptionGraceTests {
  private var encryptionTimedOut: NSError {
    NSError(domain: CBErrorDomain, code: CBError.encryptionTimedOut.rawValue)
  }

  private func makeMachine(
    bondVerified: Date?,
    appActive: Bool = true
  ) async -> (BLEStateMachine, FringeTestPeripheral, FringeDisconnectionRecorder) {
    FringeTestPeripheral.reset()
    let sm = BLEStateMachine()
    await sm.injectFringeCentralManager(FringeTestCentralManager(delegate: nil, queue: nil))
    let peripheral = makeLeakedFringePeripheral()
    let recorder = FringeDisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      recorder.append(deviceID: deviceID, error: error)
    }
    if let bondVerified {
      await sm.recordBondVerification(deviceID: FringeTestPeripheral.uuid, at: bondVerified)
    }
    if appActive {
      await sm.appDidBecomeActive()
    }
    await sm.primeFringeAutoReconnecting(peripheral: peripheral)
    return (sm, peripheral, recorder)
  }

  // MARK: - Fringe replay

  /// A recently verified encryption-timeout majority re-issues connect
  /// instead of tearing down.
  @Test
  func `out-of-range encryption timeouts with a live bond keep auto-reconnecting`() async {
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: Date().addingTimeInterval(-60))

    for _ in 1...ReconnectPolicy.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }

    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(FringeTestCentralManager.connectCallCount == ReconnectPolicy.maxAutoReconnectConnectFailures)

    await sm.cancelFringeAutoReconnectTimeout()
  }

  @Test
  func `a didConnect mid-episode is adopted cleanly and clears the failure tally`() async {
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: Date().addingTimeInterval(-60))

    for _ in 1..<ReconnectPolicy.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }
    #expect(await sm.currentAutoReconnectConnectFailures == ReconnectPolicy.maxAutoReconnectConnectFailures - 1)

    await sm.handleDidConnect(peripheral)

    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(FringeTestPeripheral.discoverServicesCallCount == 1)
    #expect(recorder.events.isEmpty)
    #expect(await sm.currentAutoReconnectConnectFailures == 0)

    await sm.cancelFringeAutoReconnectTimeout()
  }

  @Test
  func `a mixed majority of encryption timeouts with a recent bond keeps auto-reconnecting`() async {
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: Date().addingTimeInterval(-60))
    let genericTimeout = NSError(domain: CBErrorDomain, code: CBError.connectionTimeout.rawValue)

    // 3 of 5 encryption timeouts is a strict majority.
    await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    await sm.handleDidFailToConnect(peripheral, error: genericTimeout)
    await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    await sm.handleDidFailToConnect(peripheral, error: genericTimeout)
    await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)

    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(FringeTestCentralManager.connectCallCount == ReconnectPolicy.maxAutoReconnectConnectFailures)

    await sm.cancelFringeAutoReconnectTimeout()
  }

  @Test
  func `exhausted budget while inactive with no bond verification keeps auto-reconnecting`() async {
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: nil, appActive: false)
    await sm.appDidEnterBackground()

    for _ in 1...ReconnectPolicy.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }

    #expect(await sm.currentPhase.name == "autoReconnecting")
    #expect(recorder.events.isEmpty)
    #expect(FringeTestCentralManager.connectCallCount == ReconnectPolicy.maxAutoReconnectConnectFailures)

    await sm.cancelFringeAutoReconnectTimeout()
  }

  // MARK: - Dead bond still caught

  /// With no verification inside the grace window, a foreground majority
  /// escalates within one episode (`maxAutoReconnectConnectFailures` failures).
  @Test
  func `exhausted budget with a stale bond verification escalates within one episode`() async {
    let stale = Date().addingTimeInterval(-ReconnectPolicy.bondVerificationGraceInterval - 60)
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: stale)

    for _ in 1...ReconnectPolicy.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `exhausted budget with no bond verification record escalates within one episode`() async {
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: nil)

    for _ in 1...ReconnectPolicy.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  // MARK: - Definitive path untouched

  @Test
  func `a definitive bond error escalates immediately even with a recent verification`() async {
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: Date())
    let definitive = NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)

    await sm.handleDidFailToConnect(peripheral, error: definitive)

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `a definitive bond error escalates immediately while inactive`() async {
    let (sm, peripheral, recorder) = await makeMachine(bondVerified: Date(), appActive: false)
    let definitive = NSError(domain: CBErrorDomain, code: CBError.peerRemovedPairingInformation.rawValue)
    await sm.appDidEnterBackground()

    await sm.handleDidFailToConnect(peripheral, error: definitive)

    #expect(await sm.currentPhase.name == "idle")
    #expect(recorder.events.count == 1)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }
}

// MARK: - Test doubles and seams

/// Collects `(deviceID, error)` pairs delivered to `onDisconnection`.
private final class FringeDisconnectionRecorder: @unchecked Sendable {
  private(set) var events: [(deviceID: UUID, error: Error?)] = []

  func append(deviceID: UUID, error: Error?) {
    events.append((deviceID, error))
  }
}

/// A central-manager double that swallows connect calls so the re-issued
/// pending connects never reach CoreBluetooth.
private final class FringeTestCentralManager: CBCentralManager, @unchecked Sendable {
  nonisolated(unsafe) static var connectCallCount = 0

  override func connect(_ peripheral: CBPeripheral, options: [String: Any]? = nil) {
    Self.connectCallCount += 1
  }

  override func cancelPeripheralConnection(_ peripheral: CBPeripheral) {}
}

/// Retains mock peripherals for the process lifetime. `CBPeripheral` has no
/// public initializer and its `-dealloc` touches internals that a runtime-
/// allocated instance never set up, so releasing one crashes; never freeing
/// them keeps the doubles usable.
private enum FringePeripheralStore {
  nonisolated(unsafe) static var retained: [AnyObject] = []
}

/// A `CBPeripheral` double created via `class_createInstance`, so it must not
/// declare stored instance properties; per-test state lives in statics and the
/// suite is serialized.
private final class FringeTestPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  nonisolated(unsafe) static var discoverServicesCallCount = 0

  static func reset() {
    discoverServicesCallCount = 0
    FringeTestCentralManager.connectCallCount = 0
  }

  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .disconnected
  }

  override var delegate: CBPeripheralDelegate? {
    get { nil }
    set {}
  }

  override func discoverServices(_ serviceUUIDs: [CBUUID]?) {
    Self.discoverServicesCallCount += 1
  }
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeLeakedFringePeripheral() -> FringeTestPeripheral {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(FringeTestPeripheral.self, 0) as! FringeTestPeripheral
  FringePeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Actor-isolated seams that install the `.autoReconnecting` phase the
/// disconnect path would produce, then let the real handlers run.
private extension BLEStateMachine {
  func injectFringeCentralManager(_ manager: CBCentralManager) {
    centralManager = manager
  }

  func primeFringeAutoReconnecting(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
  }

  func cancelFringeAutoReconnectTimeout() {
    autoReconnectDiscoveryTimeoutTask?.cancel()
    autoReconnectDiscoveryTimeoutTask = nil
  }
}

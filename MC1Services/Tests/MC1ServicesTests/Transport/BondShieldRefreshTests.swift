import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// RSSI-driven bond verification refresh is session-live gated, refresh-only
/// (never creates), and cannot extend a shield over a preserved dead stack.
@Suite("Bond shield refresh while connected", .serialized)
@MainActor
struct BondShieldRefreshTests {
  private static let deviceID = BondRefreshTestPeripheral.uuid

  // MARK: - RSSI refresh with live session

  @Test
  func `successful RSSI with live session refreshes an existing verification`() async {
    let (sm, _) = await makeConnectedMachine(
      bondVerified: Date().addingTimeInterval(-3600),
      sessionLive: true
    )
    let before = await sm.bondVerificationDate(for: Self.deviceID)
    #expect(before != nil)

    await sm.handleDidReadRSSI(RSSI: -50, error: nil)

    let after = await sm.bondVerificationDate(for: Self.deviceID)
    #expect(after != nil)
    if let before, let after {
      #expect(after > before)
    }
  }

  @Test
  func `failed RSSI does not refresh verification`() async {
    let stamped = Date().addingTimeInterval(-3600)
    let (sm, _) = await makeConnectedMachine(bondVerified: stamped, sessionLive: true)

    await sm.handleDidReadRSSI(
      RSSI: -50,
      error: NSError(domain: CBErrorDomain, code: CBError.connectionFailed.rawValue)
    )

    let after = await sm.bondVerificationDate(for: Self.deviceID)
    #expect(after == stamped)
  }

  @Test
  func `pre-handshake connected without session-live does not refresh`() async {
    let stamped = Date().addingTimeInterval(-3600)
    let (sm, _) = await makeConnectedMachine(bondVerified: stamped, sessionLive: false)

    await sm.handleDidReadRSSI(RSSI: -50, error: nil)

    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stamped)
  }

  @Test
  func `stale session-live after phase teardown does not refresh on re-connect`() async {
    let stamped = Date().addingTimeInterval(-3600)
    let (sm, peripheral) = await makeConnectedMachine(bondVerified: stamped, sessionLive: true)
    #expect(await sm.currentAppSessionLiveDeviceID == Self.deviceID)

    // Full disconnect tears down `.connected` and must clear session-live.
    await sm.disconnect()
    #expect(await sm.currentAppSessionLiveDeviceID == nil)
    #expect(await sm.currentPhase.name == "idle")

    // Re-enter `.connected` without a completed handshake (no setAppSessionLive).
    await sm.primeConnectedWithKeepaliveForBondTests(peripheral: peripheral)
    await sm.handleDidReadRSSI(RSSI: -50, error: nil)

    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stamped)
  }

  @Test
  func `dead-stack with preserved connected phase does not refresh`() async {
    let stamped = Date().addingTimeInterval(-3600)
    let (sm, _) = await makeConnectedMachine(bondVerified: stamped, sessionLive: true)

    // Preserve path: phase stays `.connected`, session-live cleared explicitly.
    await sm.setAppSessionLive(deviceID: nil)
    await sm.handleDidReadRSSI(RSSI: -50, error: nil)

    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stamped)
  }

  @Test
  func `refresh never creates a verification that was cleared`() async {
    let (sm, _) = await makeConnectedMachine(bondVerified: Date(), sessionLive: true)
    await sm.clearBondVerification(deviceID: Self.deviceID)

    await sm.handleDidReadRSSI(RSSI: -50, error: nil)

    #expect(await sm.bondVerificationDate(for: Self.deviceID) == nil)
  }

  @Test
  func `clear then tick and tick then clear both leave nil`() async {
    let (sm, _) = await makeConnectedMachine(bondVerified: Date(), sessionLive: true)

    await sm.clearBondVerification(deviceID: Self.deviceID)
    await sm.handleDidReadRSSI(RSSI: -50, error: nil)
    #expect(await sm.bondVerificationDate(for: Self.deviceID) == nil)

    await sm.recordBondVerification(deviceID: Self.deviceID, at: Date())
    await sm.handleDidReadRSSI(RSSI: -50, error: nil)
    #expect(await sm.bondVerificationDate(for: Self.deviceID) != nil)
    await sm.clearBondVerification(deviceID: Self.deviceID)
    #expect(await sm.bondVerificationDate(for: Self.deviceID) == nil)
  }

  @Test
  func `stale bond still escalates to bondSuspect despite live-session RSSI ticks`() async {
    // Refresh with live session (stamp becomes recent), then disconnect clears live,
    // re-stamp outside grace, classify → bondSuspect. Live ticks must not defeat
    // guided re-pair once the stamp is stale again.
    let (sm, peripheral) = await makeConnectedMachine(
      bondVerified: Date().addingTimeInterval(-60),
      sessionLive: true
    )
    await sm.handleDidReadRSSI(RSSI: -50, error: nil)
    #expect(await sm.bondVerificationDate(for: Self.deviceID) != nil)

    await sm.setAppSessionLive(deviceID: nil)
    let stale = Date().addingTimeInterval(-ReconnectPolicy.bondVerificationGraceInterval - 60)
    await sm.recordBondVerification(deviceID: Self.deviceID, at: stale)

    await sm.primeFringeAutoReconnectingForBondTests(peripheral: peripheral)
    let recorder = FringeDisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      recorder.append(deviceID: deviceID, error: error)
    }

    let encryptionTimedOut = NSError(
      domain: CBErrorDomain,
      code: CBError.encryptionTimedOut.rawValue
    )
    for _ in 1...ReconnectPolicy.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }

    #expect(recorder.events.count == 1)
    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected .authenticationFailed, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `cross-launch seed after RSSI refresh is recent not handshake-aged`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let store = LastConnectionStore(defaults: defaults)
    let handshakeAge = Date().addingTimeInterval(-12.5 * 60 * 60)

    let (sm, _) = await makeConnectedMachine(bondVerified: handshakeAge, sessionLive: true)
    store.persistBondVerification(deviceID: Self.deviceID)
    // Overwrite store with handshake age via direct defaults so "persist now" is the refresh.
    defaults.set(handshakeAge, forKey: PersistenceKeys.lastBondVerifiedDate)

    await sm.setBondRefreshedHandler { deviceID in
      store.persistBondVerification(deviceID: deviceID)
    }
    await sm.handleDidReadRSSI(RSSI: -50, error: nil)

    let refreshedStoreDate = try #require(store.bondVerificationDate(for: Self.deviceID))
    #expect(Date().timeIntervalSince(refreshedStoreDate) < 5)

    // Reseed a fresh policy/SM from the store (jetsam reseed shape).
    let reseeded = BLEStateMachine()
    if let deviceID = store.bondVerifiedDeviceID,
       let verified = store.bondVerificationDate(for: deviceID) {
      await reseeded.recordBondVerification(deviceID: deviceID, at: verified)
    }
    let reseededStamp = try #require(await reseeded.bondVerificationDate(for: Self.deviceID))
    #expect(Date().timeIntervalSince(reseededStamp) < 5)
  }

  @Test
  func `joint zombie shield does not force fringe grace after preserve failures`() async {
    // Seed verification, clear session-live (preserve/dead stack), fire RSSI,
    // then exhaust encryption-timeout budget — must still escalate if stamp is
    // outside grace, not silently force fringe grace from dead-stack ticks.
    let stale = Date().addingTimeInterval(-ReconnectPolicy.bondVerificationGraceInterval - 60)
    let (sm, peripheral) = await makeConnectedMachine(bondVerified: stale, sessionLive: true)
    await sm.setAppSessionLive(deviceID: nil)

    for _ in 0..<3 {
      await sm.handleDidReadRSSI(RSSI: -50, error: nil)
    }
    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stale)

    await sm.primeFringeAutoReconnectingForBondTests(peripheral: peripheral)
    let recorder = FringeDisconnectionRecorder()
    await sm.setDisconnectionHandler { deviceID, error in
      recorder.append(deviceID: deviceID, error: error)
    }
    let encryptionTimedOut = NSError(
      domain: CBErrorDomain,
      code: CBError.encryptionTimedOut.rawValue
    )
    for _ in 1...ReconnectPolicy.maxAutoReconnectConnectFailures {
      await sm.handleDidFailToConnect(peripheral, error: encryptionTimedOut)
    }

    guard case .authenticationFailed = recorder.events.first?.error as? BLEError else {
      Issue.record("Expected bondSuspect escalation, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }

  @Test
  func `onBondRefreshed fires only when refresh mutates`() async {
    let (sm, _) = await makeConnectedMachine(
      bondVerified: Date().addingTimeInterval(-60),
      sessionLive: true
    )
    let box = BondRefreshCallbackBox()
    await sm.setBondRefreshedHandler { deviceID in
      box.record(deviceID)
    }

    await sm.handleDidReadRSSI(RSSI: -50, error: nil)
    #expect(box.deviceIDs == [Self.deviceID])

    await sm.clearBondVerification(deviceID: Self.deviceID)
    await sm.handleDidReadRSSI(RSSI: -50, error: nil)
    #expect(box.deviceIDs == [Self.deviceID])
  }

  // MARK: - Persist re-validation (CM path)

  @Test
  func `forget after RSSI hop does not resurrect bond keys`() async throws {
    // Production CM wireTransportHandlers installs onBondRefreshed with
    // epoch-gated persistBondRefreshIfStillValid. Fire real RSSI, then await
    // clearPersistedConnection, drain MainActor — both bond keys stay nil.
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }

    BondRefreshTestPeripheral.reset()
    let sm = BLEStateMachine()
    await sm.injectBondTestCentralManager(BondRefreshTestCentralManager(delegate: nil, queue: nil))
    let peripheral = makeLeakedBondRefreshPeripheral()
    let deviceID = Self.deviceID
    await sm.recordBondVerification(deviceID: deviceID, at: Date())
    await sm.primeConnectedWithKeepaliveForBondTests(peripheral: peripheral)
    await sm.setAppSessionLive(deviceID: deviceID)

    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      stateMachine: sm
    )
    LastConnectionStore(defaults: defaults).persistBondVerification(deviceID: deviceID)
    await manager.wireTransportHandlers()
    // wireTransportHandlers reseeds from store and overwrites session-live via
    // only bond seed — re-assert live for RSSI refresh.
    await sm.setAppSessionLive(deviceID: deviceID)

    await sm.handleDidReadRSSI(RSSI: -50, error: nil)
    // No artificial sleep that forces clear to win — natural race with queued hop.
    await manager.clearPersistedConnection(for: deviceID)

    // Drain MainActor so any enqueued persist hop runs to completion.
    for _ in 0..<10 {
      await Task.yield()
    }
    try await Task.sleep(for: .milliseconds(50))

    let store = LastConnectionStore(defaults: defaults)
    #expect(store.bondVerifiedDeviceID == nil)
    #expect(store.bondVerificationDate(for: deviceID) == nil)
    #expect(await sm.bondVerificationDate(for: deviceID) == nil)
  }

  @Test
  func `epoch gate blocks persist after clear even if shouldPersist was true`() async throws {
    // Check-then-act: shouldPersist returns true, clear bumps epoch mid-hop, no write.
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }

    let mock = MockBLEStateMachine()
    let deviceID = UUID()
    await mock.recordBondVerification(deviceID: deviceID, at: Date())
    await mock.setAppSessionLive(deviceID: deviceID)

    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      stateMachine: mock
    )
    LastConnectionStore(defaults: defaults).persistBondVerification(deviceID: deviceID)

    manager.bondRefreshPersistAfterShouldPersistHook = {
      await manager.clearPersistedConnection(for: deviceID)
      // Re-seed SM so a hop without epoch would re-persist after clear.
      await mock.recordBondVerification(deviceID: deviceID, at: Date())
      await mock.setAppSessionLive(deviceID: deviceID)
    }

    await manager.persistBondRefreshIfStillValid(deviceID: deviceID)

    let store = LastConnectionStore(defaults: defaults)
    #expect(store.bondVerifiedDeviceID == nil)
    #expect(store.bondVerificationDate(for: deviceID) == nil)
  }

  // MARK: - Helpers

  private func makeConnectedMachine(
    bondVerified: Date?,
    sessionLive: Bool
  ) async -> (BLEStateMachine, BondRefreshTestPeripheral) {
    BondRefreshTestPeripheral.reset()
    let sm = BLEStateMachine()
    await sm.injectBondTestCentralManager(BondRefreshTestCentralManager(delegate: nil, queue: nil))
    let peripheral = makeLeakedBondRefreshPeripheral()
    if let bondVerified {
      await sm.recordBondVerification(deviceID: Self.deviceID, at: bondVerified)
    }
    await sm.primeConnectedWithKeepaliveForBondTests(peripheral: peripheral)
    if sessionLive {
      await sm.setAppSessionLive(deviceID: Self.deviceID)
    }
    return (sm, peripheral)
  }
}

// MARK: - Test doubles

private final class BondRefreshCallbackBox: @unchecked Sendable {
  private(set) var deviceIDs: [UUID] = []
  private let lock = NSLock()

  func record(_ deviceID: UUID) {
    lock.lock()
    deviceIDs.append(deviceID)
    lock.unlock()
  }
}

/// Same shape as FringeDisconnectionRecorder — collect onDisconnection events.
private final class FringeDisconnectionRecorder: @unchecked Sendable {
  struct Event {
    let deviceID: UUID
    let error: Error?
  }

  private(set) var events: [Event] = []
  private let lock = NSLock()

  func append(deviceID: UUID, error: Error?) {
    lock.lock()
    events.append(Event(deviceID: deviceID, error: error))
    lock.unlock()
  }
}

/// Retains mock peripherals for the process lifetime (same pattern as fringe tests).
private enum BondRefreshPeripheralStore {
  nonisolated(unsafe) static var retained: [AnyObject] = []
}

private final class BondRefreshTestPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!

  static func reset() {}

  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .connected
  }

  override var delegate: CBPeripheralDelegate? {
    get { nil }
    set {}
  }

  override func discoverServices(_ serviceUUIDs: [CBUUID]?) {}
  override func readRSSI() {}
}

private final class BondRefreshTestCentralManager: CBCentralManager, @unchecked Sendable {
  override var state: CBManagerState {
    .poweredOn
  }

  override func cancelPeripheralConnection(_ peripheral: CBPeripheral) {
    // no-op — tests never talk to a real radio
  }

  override func connect(_ peripheral: CBPeripheral, options: [String: Any]? = nil) {
    // no-op
  }
}

private func makeLeakedBondRefreshPeripheral() -> BondRefreshTestPeripheral {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(BondRefreshTestPeripheral.self, 0) as! BondRefreshTestPeripheral
  BondRefreshPeripheralStore.retained.append(peripheral)
  return peripheral
}

private extension BLEStateMachine {
  func injectBondTestCentralManager(_ manager: CBCentralManager) {
    centralManager = manager
  }

  func primeConnectedWithKeepaliveForBondTests(peripheral: CBPeripheral) {
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
    startRSSIKeepalive(for: peripheral)
  }

  func primeFringeAutoReconnectingForBondTests(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
  }
}

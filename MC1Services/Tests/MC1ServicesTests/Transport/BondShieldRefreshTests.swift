import CoreBluetooth
import Foundation
@testable import MC1Services
import ObjectiveC
import Testing

/// GATT-traffic-driven bond verification refresh is session-live gated, refresh-only
/// (never creates), and cannot extend a shield over a preserved dead stack.
@Suite("Bond shield refresh while connected", .serialized)
@MainActor
struct BondShieldRefreshTests {
  private static let deviceID = BondRefreshTestPeripheral.uuid

  // MARK: - Live-traffic refresh with live session

  @Test
  func `live traffic refresh with live session updates an existing verification`() async {
    let (sm, _) = await makeConnectedMachine(
      bondVerified: Date().addingTimeInterval(-3600),
      sessionLive: true
    )
    let before = await sm.bondVerificationDate(for: Self.deviceID)
    #expect(before != nil)

    await sm.refreshBondVerificationIfLive()

    let after = await sm.bondVerificationDate(for: Self.deviceID)
    #expect(after != nil)
    if let before, let after {
      #expect(after > before)
    }
  }

  @Test
  func `successful write ACK refreshes verification and failed write ACK does not`() async {
    let stamped = Date().addingTimeInterval(-3600)
    let (sm, peripheral) = await makeConnectedMachine(bondVerified: stamped, sessionLive: true)

    let failedWrite = Task {
      try await withCheckedThrowingContinuation { continuation in
        Task {
          await sm.primePendingWriteForBondTests(continuation: continuation, sequence: 1)
          await sm.simulateDidWriteValueForBondTests(
            peripheral: peripheral,
            error: NSError(domain: CBErrorDomain, code: CBError.connectionFailed.rawValue),
            writeSequence: 1
          )
        }
      }
    }
    _ = await failedWrite.result
    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stamped)

    let succeededWrite = Task {
      try await withCheckedThrowingContinuation { continuation in
        Task {
          await sm.primePendingWriteForBondTests(continuation: continuation, sequence: 2)
          await sm.simulateDidWriteValueForBondTests(peripheral: peripheral, error: nil, writeSequence: 2)
        }
      }
    }
    _ = await succeededWrite.result
    let after = await sm.bondVerificationDate(for: Self.deviceID)
    #expect(after != nil)
    if let after {
      #expect(after > stamped)
    }
  }

  @Test
  func `inbound GATT notifications rate-limit bond refresh to once per 60s`() async throws {
    let harness = BLETransportHarness()
    let sm = BLEStateMachine(centralManager: harness.central, bondRefreshPersistInterval: 0)
    let stream = try await harness.connect(sm)
    let stamped = Date().addingTimeInterval(-3600)
    await sm.recordBondVerification(deviceID: harness.deviceID, at: stamped)
    await sm.setAppSessionLive(deviceID: harness.deviceID)

    let refreshCount = BondRefreshCounter()
    await sm.setBondRefreshedHandler { _ in refreshCount.increment() }

    let bridge = await sm.delegateHandler
    harness.rx.value = Data([0x01])
    bridge.peripheral(harness.peripheral, didUpdateValueFor: harness.rx, error: nil)
    let deadline = Date().addingTimeInterval(2)
    while refreshCount.count == 0, Date() < deadline {
      try await Task.sleep(for: .milliseconds(10))
    }
    #expect(refreshCount.count == 1)

    // Immediate second inbound frame is rate-limited inside Ingress without hopping to the actor.
    harness.rx.value = Data([0x02])
    bridge.peripheral(harness.peripheral, didUpdateValueFor: harness.rx, error: nil)
    try await Task.sleep(for: .milliseconds(30))
    #expect(refreshCount.count == 1)

    _ = stream
    await sm.shutdown()
  }

  @Test
  func `pre-handshake connected without session-live does not refresh`() async {
    let stamped = Date().addingTimeInterval(-3600)
    let (sm, _) = await makeConnectedMachine(bondVerified: stamped, sessionLive: false)

    await sm.refreshBondVerificationIfLive()

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
    await sm.primeConnectedForBondTests(peripheral: peripheral)
    await sm.refreshBondVerificationIfLive()

    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stamped)
  }

  @Test
  func `dead-stack with preserved connected phase does not refresh`() async {
    let stamped = Date().addingTimeInterval(-3600)
    let (sm, _) = await makeConnectedMachine(bondVerified: stamped, sessionLive: true)

    // Preserve path: phase stays `.connected`, session-live cleared explicitly.
    await sm.setAppSessionLive(deviceID: nil)
    await sm.refreshBondVerificationIfLive()

    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stamped)
  }

  @Test
  func `refresh never creates a verification that was cleared`() async {
    let (sm, _) = await makeConnectedMachine(bondVerified: Date(), sessionLive: true)
    await sm.clearBondVerification(deviceID: Self.deviceID)

    await sm.refreshBondVerificationIfLive()

    #expect(await sm.bondVerificationDate(for: Self.deviceID) == nil)
  }

  @Test
  func `clear then tick and tick then clear both leave nil`() async {
    let (sm, _) = await makeConnectedMachine(bondVerified: Date(), sessionLive: true)

    await sm.clearBondVerification(deviceID: Self.deviceID)
    await sm.refreshBondVerificationIfLive()
    #expect(await sm.bondVerificationDate(for: Self.deviceID) == nil)

    await sm.recordBondVerification(deviceID: Self.deviceID, at: Date())
    await sm.refreshBondVerificationIfLive()
    #expect(await sm.bondVerificationDate(for: Self.deviceID) != nil)
    await sm.clearBondVerification(deviceID: Self.deviceID)
    #expect(await sm.bondVerificationDate(for: Self.deviceID) == nil)
  }

  @Test
  func `stale bond still escalates to bondSuspect despite prior live-session refreshes`() async {
    // Live-session refreshes must not defeat guided re-pair once the bond stamp
    // becomes stale again.
    let (sm, peripheral) = await makeConnectedMachine(
      bondVerified: Date().addingTimeInterval(-60),
      sessionLive: true
    )
    await sm.refreshBondVerificationIfLive()
    #expect(await sm.bondVerificationDate(for: Self.deviceID) != nil)

    await sm.setAppSessionLive(deviceID: nil)
    let stale = Date().addingTimeInterval(-ReconnectPolicy.bondVerificationGraceInterval - 60)
    await sm.recordBondVerification(deviceID: Self.deviceID, at: stale)

    await sm.appDidBecomeActive()
    await sm.primeFringeAutoReconnectingForBondTests(peripheral: peripheral)
    let recorder = FringeDisconnectionRecorder()
    await sm.setLifecycleEventHandler { event, _ in
      guard case let .disconnected(origin, error) = event else { return }
      recorder.append(deviceID: origin.deviceID, error: error)
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
  func `cross-launch seed after bond refresh is recent not handshake-aged`() async throws {
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
    await sm.refreshBondVerificationIfLive()

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
    // Dead-stack refreshes with session-live cleared must not extend fringe grace
    // past the encryption-timeout budget.
    let stale = Date().addingTimeInterval(-ReconnectPolicy.bondVerificationGraceInterval - 60)
    let (sm, peripheral) = await makeConnectedMachine(bondVerified: stale, sessionLive: true)
    await sm.setAppSessionLive(deviceID: nil)

    for _ in 0..<3 {
      await sm.refreshBondVerificationIfLive()
    }
    #expect(await sm.bondVerificationDate(for: Self.deviceID) == stale)

    await sm.appDidBecomeActive()
    await sm.primeFringeAutoReconnectingForBondTests(peripheral: peripheral)
    let recorder = FringeDisconnectionRecorder()
    await sm.setLifecycleEventHandler { event, _ in
      guard case let .disconnected(origin, error) = event else { return }
      recorder.append(deviceID: origin.deviceID, error: error)
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
      bondVerified: Date().addingTimeInterval(-(BLEStateMachine.defaultBondRefreshPersistInterval + 60)),
      sessionLive: true
    )
    let box = BondRefreshCallbackBox()
    await sm.setBondRefreshedHandler { deviceID in
      box.record(deviceID)
    }

    await sm.refreshBondVerificationIfLive()
    #expect(box.deviceIDs == [Self.deviceID])

    await sm.clearBondVerification(deviceID: Self.deviceID)
    await sm.refreshBondVerificationIfLive()
    #expect(box.deviceIDs == [Self.deviceID])
  }

  @Test
  func `onBondRefreshed fires past persist interval, on backward clock jump, and resets on clear`() async throws {
    let sm = BLEStateMachine(bondRefreshPersistInterval: 0.05)
    await sm.injectBondTestCentralManager(BondRefreshTestCentralManager(delegate: nil, queue: nil))
    let peripheral = makeLeakedBondRefreshPeripheral()
    let initial = Date()
    await sm.recordBondVerification(deviceID: Self.deviceID, at: initial)
    await sm.primeConnectedForBondTests(peripheral: peripheral)
    await sm.setAppSessionLive(deviceID: Self.deviceID)

    let box = BondRefreshCallbackBox()
    await sm.setBondRefreshedHandler { deviceID in
      box.record(deviceID)
    }

    await sm.refreshBondVerificationIfLive()
    let refreshedInMemory = await sm.bondVerificationDate(for: Self.deviceID)
    #expect(refreshedInMemory != nil)
    if let refreshedInMemory {
      #expect(refreshedInMemory > initial)
    }
    #expect(box.deviceIDs.isEmpty)

    try await Task.sleep(for: .milliseconds(70))
    await sm.refreshBondVerificationIfLive()
    await sm.refreshBondVerificationIfLive()
    #expect(box.deviceIDs == [Self.deviceID])

    await sm.recordBondVerification(deviceID: Self.deviceID, at: Date().addingTimeInterval(3600))
    await sm.refreshBondVerificationIfLive()
    #expect(box.deviceIDs == [Self.deviceID, Self.deviceID])

    await sm.clearBondVerification(deviceID: Self.deviceID)
    await sm.refreshBondVerificationIfLive()
    #expect(box.deviceIDs.count == 2)
  }

  // MARK: - Persist re-validation (CM path)

  @Test
  func `forget after bond refresh hop does not resurrect bond keys`() async throws {
    // A queued refresh hop must not re-persist bond keys after
    // `clearPersistedConnection` runs.
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }

    BondRefreshTestPeripheral.reset()
    let sm = BLEStateMachine()
    await sm.injectBondTestCentralManager(BondRefreshTestCentralManager(delegate: nil, queue: nil))
    let peripheral = makeLeakedBondRefreshPeripheral()
    let deviceID = Self.deviceID
    await sm.recordBondVerification(deviceID: deviceID, at: Date())
    await sm.primeConnectedForBondTests(peripheral: peripheral)
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
    // only bond seed — re-assert live for refresh.
    await sm.setAppSessionLive(deviceID: deviceID)

    await sm.refreshBondVerificationIfLive()
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
    await sm.primeConnectedForBondTests(peripheral: peripheral)
    if sessionLive {
      await sm.setAppSessionLive(deviceID: Self.deviceID)
    }
    return (sm, peripheral)
  }
}

// MARK: - Test doubles

private final class BondRefreshCounter: @unchecked Sendable {
  private let lock = NSLock()
  private var value = 0

  var count: Int {
    lock.withLock { value }
  }

  func increment() {
    lock.withLock { value += 1 }
  }
}

private final class BondRefreshCallbackBox: @unchecked Sendable {
  private(set) var deviceIDs: [UUID] = []
  private let lock = NSLock()

  func record(_ deviceID: UUID) {
    lock.lock()
    deviceIDs.append(deviceID)
    lock.unlock()
  }
}

/// Records disconnected lifecycle events for the callback assertions.
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

  func primeConnectedForBondTests(peripheral: CBPeripheral) {
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

  func primePendingWriteForBondTests(continuation: CheckedContinuation<Void, Error>, sequence: UInt64) {
    pendingWriteContinuation = continuation
    pendingWriteSequence = sequence
  }

  func simulateDidWriteValueForBondTests(peripheral: CBPeripheral, error: (any Error)?, writeSequence: UInt64) {
    let tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )
    handleDidWriteValue(peripheral, characteristic: tx, error: error, writeSequence: writeSequence)
  }

  func primeFringeAutoReconnectingForBondTests(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
  }
}

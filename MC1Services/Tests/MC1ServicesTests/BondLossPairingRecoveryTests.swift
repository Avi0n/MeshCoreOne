import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import ObjectiveC
import SwiftData
import Testing

/// A radio that loses its bond mid-session is recovered through the guided
/// "remove and retry" flow, which calls `removeFailedPairing`. That path must
/// preserve the radio's data: demoting the `Device` row to a ghost keeps the
/// publicKey ↔ radioID bridge alive, so re-pairing the same physical radio
/// resolves the original radioID and reattaches its contacts, messages, and
/// channels. A hard delete would drop the bridge and orphan every child row.
@Suite("Bond-loss pairing recovery preserves radio data")
@MainActor
struct BondLossPairingRecoveryTests {
  private static let radioPublicKey = Data(repeating: 0x7B, count: 32)

  private static let testCapabilities = DeviceCapabilities(
    firmwareVersion: 9,
    maxContacts: 100,
    maxChannels: 8,
    blePin: 0,
    firmwareBuild: "01 Jan 2025",
    model: "T-Deck",
    version: "v1.13.0"
  )

  private static func makeSelfInfo(publicKey: Data = radioPublicKey) -> SelfInfo {
    SelfInfo(
      advertisementType: 0,
      txPower: 20,
      maxTxPower: 20,
      publicKey: publicKey,
      latitude: 0,
      longitude: 0,
      multiAcks: 2,
      advertisementLocationPolicy: 0,
      telemetryModeEnvironment: 0,
      telemetryModeLocation: 0,
      telemetryModeBase: 2,
      manualAddContacts: false,
      radioFrequency: 915.0,
      radioBandwidth: 250.0,
      radioSpreadingFactor: 10,
      radioCodingRate: 5,
      name: "TestNode"
    )
  }

  /// A never-connected session so the up-front `getAutoAddConfig` roundtrip
  /// fails fast; the connect ceremony swallows it and proceeds.
  private func makeOfflineSession() -> MeshCoreSession {
    MeshCoreSession(
      transport: SimulatorMockTransport(),
      configuration: SessionConfiguration(defaultTimeout: 0.5, clientIdentifier: "BondLossRecoveryTest")
    )
  }

  @Test
  func `removeFailedPairing keeps the radio's data reachable when the same radio re-pairs`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    // First connect: fresh pairing mints a radioID for this publicKey.
    let firstBLEID = UUID()
    let firstConnect = try await manager.buildServicesAndSaveDevice(
      deviceID: firstBLEID,
      session: makeOfflineSession(),
      selfInfo: Self.makeSelfInfo(),
      capabilities: Self.testCapabilities
    )
    let originalRadioID = firstConnect.radioID
    let store = firstConnect.services.dataStore

    // Seed the per-radio children a synced radio accumulates.
    let contactID = UUID()
    let contact = ContactDTO(
      id: contactID,
      radioID: originalRadioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "FieldContact",
      typeRawValue: 0,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    try await store.saveContact(contact)

    let messageID = UUID()
    let message = MessageDTO(from: Message(
      id: messageID,
      radioID: originalRadioID,
      contactID: contactID,
      text: "message before bond loss",
      timestamp: 1_700_000_000
    ))
    try await store.saveMessage(message)

    let channel = ChannelDTO(
      id: UUID(),
      radioID: originalRadioID,
      index: 3,
      name: "FieldChannel",
      secret: Data(repeating: 1, count: 16),
      isEnabled: true,
      lastMessageDate: nil,
      unreadCount: 0,
      floodScope: .inherit
    )
    try await store.saveChannel(channel)

    // Guided recovery from a dead bond: the destructive "Remove & Retry" button.
    await manager.removeFailedPairing(deviceID: firstBLEID)

    // The children survive the demotion; no cascade delete.
    #expect(try await store.fetchContacts(radioID: originalRadioID).contains { $0.id == contactID })
    #expect(try await store.fetchMessages(contactID: contactID).contains { $0.id == messageID })
    #expect(try await store.fetchChannels(radioID: originalRadioID).contains { $0.name == "FieldChannel" })

    // Re-pair the same radio over a fresh CoreBluetooth handle. The publicKey
    // fallback must recover the original partition key from the ghost row.
    let secondBLEID = UUID()
    #expect(secondBLEID != firstBLEID)
    let secondConnect = try await manager.buildServicesAndSaveDevice(
      deviceID: secondBLEID,
      session: makeOfflineSession(),
      selfInfo: Self.makeSelfInfo(),
      capabilities: Self.testCapabilities
    )

    #expect(secondConnect.radioID == originalRadioID)

    // Every child reattaches to the resolved radioID rather than being orphaned.
    #expect(try await store.fetchContacts(radioID: secondConnect.radioID).contains { $0.id == contactID })
    #expect(try await store.fetchMessages(contactID: contactID).contains { $0.id == messageID })
    #expect(try await store.fetchChannels(radioID: secondConnect.radioID).contains { $0.name == "FieldChannel" })
  }

  // MARK: - Bond-verification clear

  @Test
  func `clearPersistedConnection clears forgotten device bond and leaves the other intact`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let env = try ConnectionManager.createForPairingTesting(defaults: defaults)
    defer { env.cleanup() }

    let deviceA = UUID()
    let deviceB = UUID()
    let verifiedAt = Date()

    await env.stateMachine.recordBondVerification(deviceID: deviceA, at: verifiedAt)
    await env.stateMachine.recordBondVerification(deviceID: deviceB, at: verifiedAt)
    // Bond slot holds B; last-connected is A — clear must target forgotten A
    // (not the bond-slot holder) and must not skip when A is not last-connected.
    env.manager.persistConnection(deviceID: deviceA, radioID: UUID(), deviceName: "Radio A")
    LastConnectionStore(defaults: defaults).persistBondVerification(deviceID: deviceB)

    await env.manager.clearPersistedConnection(for: deviceA)

    #expect(await env.stateMachine.recordedBondVerifications[deviceA] == nil)
    // B's stamp survives; exact Date may be re-seeded from the store by wiring.
    #expect(await env.stateMachine.recordedBondVerifications[deviceB] != nil)
    _ = verifiedAt

    let store = LastConnectionStore(defaults: defaults)
    #expect(store.deviceID == nil)
    #expect(store.bondVerificationDate(for: deviceB) != nil)
    #expect(store.bondVerifiedDeviceID == deviceB)
  }

  @Test
  func `holder bond slot survives forgetting a non-holder`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let env = try ConnectionManager.createForPairingTesting(defaults: defaults)
    defer { env.cleanup() }

    let deviceA = UUID()
    let deviceB = UUID()
    env.manager.persistConnection(deviceID: deviceA, radioID: UUID(), deviceName: "Radio A")
    LastConnectionStore(defaults: defaults).persistBondVerification(deviceID: deviceB)

    await env.manager.clearPersistedConnection(for: deviceA)

    let store = LastConnectionStore(defaults: defaults)
    #expect(store.bondVerifiedDeviceID == deviceB)
    #expect(store.bondVerificationDate(for: deviceB) != nil)
  }

  @Test
  func `empty bond slot still clears forgotten device in-memory verification`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let env = try ConnectionManager.createForPairingTesting(defaults: defaults)
    defer { env.cleanup() }

    let deviceA = UUID()
    await env.stateMachine.recordBondVerification(deviceID: deviceA, at: Date())
    // No persistBondVerification — bond slot empty; in-memory clear must still run.

    await env.manager.clearPersistedConnection(for: deviceA)

    #expect(await env.stateMachine.recordedBondVerifications[deviceA] == nil)
  }

  @Test
  func `forgetting a non-last-connected device still clears its bond verification`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let env = try ConnectionManager.createForPairingTesting(defaults: defaults)
    defer { env.cleanup() }

    let deviceA = UUID()
    let deviceB = UUID()
    env.manager.persistConnection(deviceID: deviceA, radioID: UUID(), deviceName: "Radio A")
    await env.stateMachine.recordBondVerification(deviceID: deviceB, at: Date())
    LastConnectionStore(defaults: defaults).persistBondVerification(deviceID: deviceB)

    // removeFailedPairing is the async forget path; must clear B even though A is last-connected.
    await env.manager.removeFailedPairing(deviceID: deviceB)

    #expect(await env.stateMachine.recordedBondVerifications[deviceB] == nil)
    let store = LastConnectionStore(defaults: defaults)
    #expect(store.deviceID == deviceA)
    #expect(store.bondVerificationDate(for: deviceB) == nil)
    #expect(store.bondVerifiedDeviceID == nil)
  }

  @Test
  func `awaited forget clears bond before encryption-timeout budget classifies as bondSuspect`() async throws {
    // Single owner: seed and clear on the real SM map, then classify through the
    // same actor's handleDidFailToConnect path (no dual-tracked local policy).
    BondRefreshClearPeripheral.reset()
    let sm = BLEStateMachine()
    await sm.injectBondClearTestCentral(BondRefreshClearCentralManager(delegate: nil, queue: nil))
    let peripheral = makeLeakedBondClearPeripheral()
    let deviceID = BondRefreshClearPeripheral.uuid

    await sm.recordBondVerification(deviceID: deviceID, at: Date())
    #expect(await sm.bondVerificationDate(for: deviceID) != nil)

    // Production async clear path (same await as forget/removeFailedPairing).
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      stateMachine: sm
    )
    await manager.clearPersistedConnection(for: deviceID)
    #expect(await sm.bondVerificationDate(for: deviceID) == nil)

    await sm.primeBondClearAutoReconnecting(peripheral: peripheral)
    let recorder = BondClearDisconnectionRecorder()
    await sm.setDisconnectionHandler { id, error in
      recorder.append(deviceID: id, error: error)
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
      Issue.record("Expected .authenticationFailed without grace, got \(String(describing: recorder.events.first?.error))")
      return
    }
  }
}

// MARK: - Clear-before-classify doubles

private final class BondClearDisconnectionRecorder: @unchecked Sendable {
  private(set) var events: [(deviceID: UUID, error: Error?)] = []
  private let lock = NSLock()

  func append(deviceID: UUID, error: Error?) {
    lock.lock()
    events.append((deviceID, error))
    lock.unlock()
  }
}

private enum BondRefreshClearPeripheralStore {
  nonisolated(unsafe) static var retained: [AnyObject] = []
}

private final class BondRefreshClearPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID(uuidString: "CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")!
  static func reset() {}

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

  override func discoverServices(_ serviceUUIDs: [CBUUID]?) {}
}

private final class BondRefreshClearCentralManager: CBCentralManager, @unchecked Sendable {
  override var state: CBManagerState {
    .poweredOn
  }

  override func cancelPeripheralConnection(_ peripheral: CBPeripheral) {}
  override func connect(_ peripheral: CBPeripheral, options: [String: Any]? = nil) {}
}

private func makeLeakedBondClearPeripheral() -> BondRefreshClearPeripheral {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(BondRefreshClearPeripheral.self, 0) as! BondRefreshClearPeripheral
  BondRefreshClearPeripheralStore.retained.append(peripheral)
  return peripheral
}

private extension BLEStateMachine {
  func injectBondClearTestCentral(_ manager: CBCentralManager) {
    centralManager = manager
  }

  func primeBondClearAutoReconnecting(peripheral: CBPeripheral) {
    phase = .autoReconnecting(peripheral: peripheral, tx: nil, rx: nil)
    phaseStartTime = Date()
  }
}

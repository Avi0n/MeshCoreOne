import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import SwiftData
import Testing

/// Reconnecting the same radio over a fresh CoreBluetooth handle must not
/// re-partition its data. `buildServicesAndSaveDevice` resolves the radioID by
/// missing on `fetchDevice(id:)` (the volatile BLE UUID changed) and falling
/// back to `fetchDevice(publicKey:)`, so every per-radio row persisted under the
/// first connection stays reachable. Losing that fallback would mint a fresh
/// radioID and orphan the radio's `PendingSend` rows.
@Suite("Connect-path radioID resolution")
@MainActor
struct ConnectRadioIDResolutionTests {
  private static let radioPublicKey = Data(repeating: 0xC3, count: 32)

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
  /// fails fast with `.notConnected`; the ceremony swallows it and proceeds.
  private func makeOfflineSession() -> MeshCoreSession {
    MeshCoreSession(
      transport: SimulatorMockTransport(),
      configuration: SessionConfiguration(defaultTimeout: 0.5, clientIdentifier: "RadioIDResolutionTest")
    )
  }

  @Test
  func `Reconnect with a changed BLE id but same publicKey resolves the original radioID and keeps its PendingSends reachable`() async throws {
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

    // The second connect hydrates the chat send queue, whose DM drain
    // terminally deletes any envelope missing its Contact row ("contact
    // deleted") or its Message row (sendFailed). A complete fixture — the
    // same Contact + Message + PendingSend triple a real enqueue leaves on
    // disk — makes the offline session's failure transient instead, so the
    // drain parks and the row deterministically survives to the assertion
    // below.
    let contactID = UUID()
    let contact = ContactDTO(
      id: contactID,
      radioID: originalRadioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: "QueuedRecipient",
      typeRawValue: 0,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    try await firstConnect.services.dataStore.saveContact(contact)

    // A per-radio PendingSend row enqueued while connected to this radio.
    let pendingMessageID = UUID()
    let message = MessageDTO(from: Message(
      id: pendingMessageID,
      radioID: originalRadioID,
      contactID: contactID,
      text: "queued before reconnect",
      timestamp: 1_700_000_000
    ))
    try await firstConnect.services.dataStore.saveMessage(message)

    let pending = PendingSendDTO(
      id: UUID(),
      radioID: originalRadioID,
      messageID: pendingMessageID,
      kind: .dm,
      contactID: contactID,
      channelIndex: nil,
      isResend: false,
      messageText: "queued before reconnect",
      messageTimestamp: 1_700_000_000,
      localNodeName: "TestNode",
      sequence: 1,
      enqueuedAt: Date(timeIntervalSince1970: 1_700_000_000)
    )
    try await firstConnect.services.dataStore.upsertPendingSend(pending)

    // Reconnect the SAME radio presenting a DIFFERENT CoreBluetooth handle
    // (re-pair re-mints the peripheral UUID) but the SAME publicKey.
    let secondBLEID = UUID()
    #expect(secondBLEID != firstBLEID)
    let secondConnect = try await manager.buildServicesAndSaveDevice(
      deviceID: secondBLEID,
      session: makeOfflineSession(),
      selfInfo: Self.makeSelfInfo(),
      capabilities: Self.testCapabilities
    )

    // The publicKey fallback must recover the original partition key rather
    // than mint a fresh one for the changed BLE id.
    #expect(secondConnect.radioID == originalRadioID)

    // The pre-reconnect PendingSend is still reachable under the resolved radioID.
    // Read back through the store that wrote it: both PersistenceStores share the
    // in-memory ModelContainer, and a first cross-context fetch on the second
    // store can miss the just-committed row, so query the context that owns it.
    let rows = try await firstConnect.services.dataStore.fetchPendingSends(radioID: secondConnect.radioID)
    #expect(rows.contains { $0.messageID == pendingMessageID })
  }

  private let wifiMethod = ConnectionMethod.wifi(host: "10.0.0.4", port: 5000, displayName: nil)

  private func save(
    manager: ConnectionManager,
    deviceID: UUID,
    publicKey: Data,
    methods: [ConnectionMethod]
  ) async throws -> UUID {
    let connected = try await manager.buildServicesAndSaveDevice(
      deviceID: deviceID,
      session: makeOfflineSession(),
      selfInfo: Self.makeSelfInfo(publicKey: publicKey),
      capabilities: Self.testCapabilities,
      connectionMethods: methods
    )
    return connected.radioID
  }

  @Test
  func `wifi save keeps a bluetooth row id and radioID`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x11, count: 32)
    let peripheralID = UUID()
    let radioID = try await save(
      manager: manager,
      deviceID: peripheralID,
      publicKey: publicKey,
      methods: [.bluetooth(peripheralUUID: peripheralID, displayName: nil)]
    )

    let wifiID = await manager.wifiDeviceID(publicKey: publicKey)
    #expect(wifiID == peripheralID)
    let savedRadioID = try await save(
      manager: manager,
      deviceID: wifiID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [peripheralID])
    #expect(savedRadioID == radioID)
    #expect(rows.first?.radioID == radioID)
    #expect(rows.first?.connectionMethods == [
      .bluetooth(peripheralUUID: peripheralID, displayName: nil),
      wifiMethod
    ])
    #expect(try await manager.persistenceStore.fetchDevice(id: DeviceIdentity.deriveUUID(from: publicKey)) == nil)
  }

  @Test
  func `bluetooth save then wifi reconnect save keeps one row at the peripheral id`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x22, count: 32)
    let peripheralID = UUID()
    _ = try await save(
      manager: manager,
      deviceID: peripheralID,
      publicKey: publicKey,
      methods: [.bluetooth(peripheralUUID: peripheralID, displayName: nil)]
    )

    let wifiID = await manager.wifiDeviceID(publicKey: publicKey)
    _ = try await save(manager: manager, deviceID: wifiID, publicKey: publicKey, methods: [])

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [peripheralID])
    #expect(try await manager.persistenceStore.fetchDevice(id: peripheralID) != nil)
  }

  @Test
  func `wifi reconnect save then bluetooth save keeps one row at the peripheral id`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x33, count: 32)
    let derivedID = await manager.wifiDeviceID(publicKey: publicKey)
    #expect(derivedID == DeviceIdentity.deriveUUID(from: publicKey))
    let radioID = try await save(manager: manager, deviceID: derivedID, publicKey: publicKey, methods: [])

    let peripheralID = UUID()
    let savedRadioID = try await save(
      manager: manager,
      deviceID: peripheralID,
      publicKey: publicKey,
      methods: [.bluetooth(peripheralUUID: peripheralID, displayName: nil)]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [peripheralID])
    #expect(savedRadioID == radioID)
  }

  @Test
  func `wifi save with no row uses the derived id`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x44, count: 32)
    let derivedID = DeviceIdentity.deriveUUID(from: publicKey)
    #expect(await manager.wifiDeviceID(publicKey: publicKey) == derivedID)

    _ = try await save(manager: manager, deviceID: derivedID, publicKey: publicKey, methods: [wifiMethod])

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [derivedID])
  }

  @Test
  func `wifi save keeps a wifi-only row id`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x55, count: 32)
    let derivedID = DeviceIdentity.deriveUUID(from: publicKey)
    let radioID = try await save(
      manager: manager,
      deviceID: derivedID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let wifiID = await manager.wifiDeviceID(publicKey: publicKey)
    #expect(wifiID == derivedID)
    let savedRadioID = try await save(
      manager: manager,
      deviceID: wifiID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [derivedID])
    #expect(savedRadioID == radioID)
  }

  @Test
  func `wifi save after bluetooth removal keeps the peripheral id`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x66, count: 32)
    let peripheralID = UUID()
    let radioID = try await save(
      manager: manager,
      deviceID: peripheralID,
      publicKey: publicKey,
      methods: [
        .bluetooth(peripheralUUID: peripheralID, displayName: nil),
        wifiMethod
      ]
    )
    let stripped = try await manager.persistenceStore.removeBluetoothMethod(peripheralID: peripheralID)
    #expect(stripped?.id == peripheralID)

    let wifiID = await manager.wifiDeviceID(publicKey: publicKey)
    #expect(wifiID == peripheralID)
    let savedRadioID = try await save(
      manager: manager,
      deviceID: wifiID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [peripheralID])
    #expect(savedRadioID == radioID)
    #expect(try await manager.persistenceStore.fetchDevice(id: DeviceIdentity.deriveUUID(from: publicKey)) == nil)
  }

  @Test
  func `wifi save re-keys a legacy derived row that still has bluetooth`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x77, count: 32)
    let peripheralID = UUID()
    let derivedID = UUID()
    #expect(derivedID != peripheralID)
    let legacy = DeviceDTO.testDevice(id: derivedID, publicKey: publicKey).copy {
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: peripheralID, displayName: nil),
        wifiMethod
      ]
    }
    try await manager.persistenceStore.saveDevice(legacy)

    let wifiID = await manager.wifiDeviceID(publicKey: publicKey)
    #expect(wifiID == peripheralID)
    let savedRadioID = try await save(
      manager: manager,
      deviceID: wifiID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [peripheralID])
    #expect(savedRadioID == legacy.radioID)
    #expect(try await manager.persistenceStore.fetchDevice(id: derivedID) == nil)
  }

  @Test
  func `wifi save keeps a backup shadow id`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x88, count: 32)
    let shadowID = UUID()
    let shadow = DeviceDTO.testDevice(id: shadowID, publicKey: publicKey).copy {
      $0.connectionMethods = [wifiMethod]
    }
    try await manager.persistenceStore.saveDevice(shadow)

    let wifiID = await manager.wifiDeviceID(publicKey: publicKey)
    #expect(wifiID == shadowID)
    let savedRadioID = try await save(
      manager: manager,
      deviceID: wifiID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [shadowID])
    #expect(savedRadioID == shadow.radioID)
  }

  @Test
  func `wifi save keeps a ghost id`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0x99, count: 32)
    let ghostID = UUID()
    let ghost = DeviceDTO.testDevice(id: ghostID, publicKey: publicKey, isActive: false)
    try await manager.persistenceStore.saveDevice(ghost)

    let wifiID = await manager.wifiDeviceID(publicKey: publicKey)
    #expect(wifiID == ghostID)
    let savedRadioID = try await save(
      manager: manager,
      deviceID: wifiID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [ghostID])
    #expect(savedRadioID == ghost.radioID)
  }

  @Test
  func `bluetooth save after a wifi-only row re-keys to the peripheral`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0xAA, count: 32)
    let derivedID = DeviceIdentity.deriveUUID(from: publicKey)
    let radioID = try await save(
      manager: manager,
      deviceID: derivedID,
      publicKey: publicKey,
      methods: [wifiMethod]
    )

    let peripheralID = UUID()
    let savedRadioID = try await save(
      manager: manager,
      deviceID: peripheralID,
      publicKey: publicKey,
      methods: [.bluetooth(peripheralUUID: peripheralID, displayName: nil)]
    )

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.count == 1)
    #expect(rows.first?.id == peripheralID)
    #expect(savedRadioID == radioID)
    #expect(rows.first?.connectionMethods == [
      wifiMethod,
      .bluetooth(peripheralUUID: peripheralID, displayName: nil)
    ])
  }

  @Test
  func `wifiDeviceID prefers a bluetooth row over a newer ghost with the same key`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()
    let publicKey = Data(repeating: 0xBB, count: 32)
    let peripheralID = UUID()
    let ghost = DeviceDTO.testDevice(
      id: UUID(),
      publicKey: publicKey,
      lastConnected: Date(),
      isActive: false
    )
    let bluetooth = DeviceDTO.testDevice(
      id: peripheralID,
      publicKey: publicKey,
      lastConnected: Date().addingTimeInterval(-3600),
      isActive: false
    ).copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: peripheralID, displayName: nil)]
    }
    try await manager.persistenceStore.saveDevice(ghost)
    try await manager.persistenceStore.saveDevice(bluetooth)

    #expect(await manager.wifiDeviceID(publicKey: publicKey) == peripheralID)
  }
}

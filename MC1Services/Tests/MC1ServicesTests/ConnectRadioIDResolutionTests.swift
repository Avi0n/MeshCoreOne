import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// Connection entry points must preserve the radio partition across volatile handle changes.
@Suite("Connect-path radioID resolution")
@MainActor
struct ConnectRadioIDResolutionTests {
  private static let radioPublicKey = Data(repeating: 0xC3, count: 32)

  private func withManager(
    publicKey: Data = ConnectRadioIDResolutionTests.radioPublicKey,
    _ operation: @MainActor (ConnectionManager, ConnectionTestRadioTransport) async throws -> Void
  ) async throws {
    let environment = try ConnectionManagerRadioTestHarness(publicKey: publicKey)
    do { try await operation(environment.manager, environment.ble) } catch {
      await environment.close()
      throw error
    }
    await environment.close()
  }

  @Test
  func `Reconnect with a changed BLE id but same publicKey resolves the original radioID and keeps its PendingSends reachable`() async throws {
    try await withManager(publicKey: Self.radioPublicKey) { manager, radio in
      // First connect: fresh pairing mints a radioID for this publicKey.
      let firstBLEID = UUID()
      let firstConnect = try await saveConnection(manager: manager, deviceID: firstBLEID, methods: [
        .bluetooth(peripheralUUID: firstBLEID, displayName: nil)
      ])
      let originalRadioID = firstConnect.device.radioID

      // The queue requires the same Contact, Message, and PendingSend triple
      // as a real enqueue so hydration cannot discard an incomplete fixture.
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

      var packet = Data([ResponseCode.contact.rawValue])
      packet.append(contact.publicKey)
      packet.append(contentsOf: [ContactType.chat.rawValue, 0, 0xFF])
      packet.append(Data(repeating: 0, count: 64))
      let name = Data(contact.name.utf8.prefix(31))
      packet.append(name)
      packet.append(Data(repeating: 0, count: 32 - name.count))
      packet.append(Data(repeating: 0, count: 16))
      await radio.setResponsePackets(for: .getContacts, packets: [
        Data([ResponseCode.contactStart.rawValue, 1, 0, 0, 0]), packet,
        Data([ResponseCode.contactEnd.rawValue, 1, 0, 0, 0])
      ])

      // Reconnect the same radio presenting a different CoreBluetooth handle
      // (re-pair re-mints the peripheral UUID) but the same publicKey.
      let secondBLEID = UUID()
      #expect(secondBLEID != firstBLEID)
      let secondConnect = try await saveConnection(manager: manager, deviceID: secondBLEID, methods: [
        .bluetooth(peripheralUUID: secondBLEID, displayName: nil)
      ])

      // The publicKey fallback must recover the original partition key rather
      // than mint a fresh one for the changed BLE id.
      #expect(secondConnect.device.radioID == originalRadioID)

      // The pre-reconnect PendingSend is still reachable under the resolved radioID.
      let rows = try await firstConnect.services.dataStore.fetchPendingSends(radioID: secondConnect.device.radioID)
      #expect(rows.contains { $0.messageID == pendingMessageID })
    }
  }

  private let wifiMethod = ConnectionMethod.wifi(host: "10.0.0.4", port: 5000, displayName: nil)

  private func save(
    manager: ConnectionManager,
    deviceID: UUID,
    publicKey: Data,
    methods: [ConnectionMethod]
  ) async throws -> UUID {
    let connected = try await saveConnection(manager: manager, deviceID: deviceID, methods: methods)
    #expect(connected.device.publicKey == publicKey)
    return connected.device.radioID
  }

  private func saveConnection(
    manager: ConnectionManager, deviceID: UUID, methods: [ConnectionMethod]
  ) async throws -> ConnectionSnapshot {
    var result: ConnectionResult?
    for method in methods.isEmpty ? [wifiMethod] : methods {
      switch method {
      case let .bluetooth(peripheralUUID, _):
        result = try await manager.connect(to: peripheralUUID, forceFullSync: true, forceReconnect: true)
      case let .wifi(host, port, _):
        result = try await manager.connectViaWiFi(host: host, port: port, forceFullSync: true)
      }
    }
    guard case let .operational(snapshot) = result else {
      throw ConnectionError.notConnected
    }
    #expect(snapshot.device.id == deviceID)
    return snapshot
  }

  @Test
  func `wifi save keeps a bluetooth row id and radioID`() async throws {
    try await withManager(publicKey: Data(repeating: 0x11, count: 32)) { manager, _ in
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
  }

  @Test
  func `bluetooth save then wifi reconnect save keeps one row at the peripheral id`() async throws {
    try await withManager(publicKey: Data(repeating: 0x22, count: 32)) { manager, _ in
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
  }

  @Test
  func `wifi reconnect save then bluetooth save keeps one row at the peripheral id`() async throws {
    try await withManager(publicKey: Data(repeating: 0x33, count: 32)) { manager, _ in
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
  }

  @Test
  func `wifi save with no row uses the derived id`() async throws {
    try await withManager(publicKey: Data(repeating: 0x44, count: 32)) { manager, _ in
      let publicKey = Data(repeating: 0x44, count: 32)
      let derivedID = DeviceIdentity.deriveUUID(from: publicKey)
      #expect(await manager.wifiDeviceID(publicKey: publicKey) == derivedID)

      _ = try await save(manager: manager, deviceID: derivedID, publicKey: publicKey, methods: [wifiMethod])

      let rows = try await manager.persistenceStore.fetchDevices()
      #expect(rows.map(\.id) == [derivedID])
    }
  }

  @Test
  func `wifi save keeps a wifi-only row id`() async throws {
    try await withManager(publicKey: Data(repeating: 0x55, count: 32)) { manager, _ in
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
  }

  @Test
  func `wifi save after bluetooth removal keeps the peripheral id`() async throws {
    try await withManager(publicKey: Data(repeating: 0x66, count: 32)) { manager, _ in
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
  }

  @Test
  func `wifi save re-keys a legacy derived row that still has bluetooth`() async throws {
    try await withManager(publicKey: Data(repeating: 0x77, count: 32)) { manager, _ in
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
  }

  @Test
  func `wifi save keeps a backup shadow id`() async throws {
    try await withManager(publicKey: Data(repeating: 0x88, count: 32)) { manager, _ in
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
  }

  @Test
  func `wifi save keeps a ghost id`() async throws {
    try await withManager(publicKey: Data(repeating: 0x99, count: 32)) { manager, _ in
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
  }

  @Test
  func `bluetooth save after a wifi-only row re-keys to the peripheral`() async throws {
    try await withManager(publicKey: Data(repeating: 0xAA, count: 32)) { manager, _ in
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
  }

  @Test
  func `wifiDeviceID prefers a bluetooth row over a newer ghost with the same key`() async throws {
    try await withManager(publicKey: Data(repeating: 0xBB, count: 32)) { manager, _ in
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
}

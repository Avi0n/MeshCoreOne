#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Authenticated registration rekey boundary")
@MainActor
struct ConnectionRegistrationRekeyTests {
  @Test
  func `superseded authenticated rekey reconnects through its durable saved Bluetooth target`() async throws {
    let env = try ConnectionManagerRadioTestHarness(publicKey: Data(repeating: 0xD5, count: 32))
    let manager = env.manager
    let oldID = UUID()
    let newID = UUID()
    let wifi = ConnectionMethod.wifi(host: "saved.radio", port: 5123, displayName: "Saved WiFi")
    let lastConnected = Date(timeIntervalSince1970: 1_700_000_000)
    let saved = DeviceDTO.testDevice(id: oldID, publicKey: env.ble.publicKey, lastConnected: lastConnected, isActive: false).copy {
      $0.connectionMethods = [wifi, .bluetooth(peripheralUUID: oldID, displayName: "Field Bluetooth")]
    }
    var connecting: Task<ConnectionResult, Error>?
    var superseding: Task<ConnectionLossReceipt, Never>?
    do {
      try await manager.persistenceStore.saveDevice(saved)
      let pending = try await seedPendingSend(store: manager.persistenceStore, radioID: saved.radioID, radio: env.ble)
      env.pairing.isSessionActive = true
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: newID, displayName: "New pairing")])
      var capabilities = Data([ResponseCode.deviceInfo.rawValue, 14, 1, 1])
      capabilities.append(Data(repeating: 0, count: 76))
      capabilities.append(1)
      await env.ble.setResponsePackets(for: .deviceQuery, packets: [capabilities])
      await env.ble.setResponsePackets(for: .getRepeatFreq, packets: [Data([ResponseCode.allowedRepeatFreq.rawValue])])
      await env.ble.holdReplies(for: .getRepeatFreq)
      let attempt = Task { try await manager.connect(to: newID, forceFullSync: true, forceReconnect: true) }
      connecting = attempt
      try await waitUntil {
        await env.ble.sentData.contains { $0.first == CommandCode.getRepeatFreq.rawValue }
      }
      #expect(try await manager.persistenceStore.fetchDevice(id: newID)?.radioID == saved.radioID)
      #expect(manager.installedLink == nil)
      let disconnecting = Task { await manager.disconnect(reason: .userInitiated) }
      superseding = disconnecting
      try await waitUntil { manager.desiredRequest?.target == .disconnected }
      await env.ble.releaseReplies(for: .getRepeatFreq)
      let outcome = try await attempt.value
      if case .superseded = outcome {} else { Issue.record("Rekey attempt must lose publication to the admitted disconnect") }
      _ = await disconnecting.value
      await manager.metadataCommitTask?.value

      let registered = try #require(try await manager.persistenceStore.fetchDevice(id: newID))
      #expect(try await manager.persistenceStore.fetchDevice(id: oldID) == nil)
      #expect(registered.radioID == saved.radioID)
      #expect(registered.connectionMethods == [wifi, .bluetooth(peripheralUUID: newID, displayName: "Field Bluetooth")],
              "Durable authenticated registration must rekey the existing Bluetooth method in place before selection")
      #expect(registered.savedDeviceConnectTarget == .bluetooth(peripheralID: newID))
      #expect(registered.lastConnected == lastConnected)
      #expect(!registered.isActive)
      #expect(try await manager.persistenceStore.fetchPendingSends(radioID: registered.radioID).contains { $0.id == pending.id })
      #expect(await manager.wifiDeviceID(publicKey: registered.publicKey) == newID)

      let reconnected: ConnectionResult = switch registered.savedDeviceConnectTarget {
      case let .bluetooth(peripheralID):
        try await manager.connect(to: peripheralID, forceFullSync: true, forceReconnect: true)
      case let .wifi(host, port):
        try await manager.connectViaWiFi(host: host, port: port, forceFullSync: true)
      }
      let snapshot: ConnectionSnapshot
      if case let .operational(current) = reconnected { snapshot = current } else { throw ConnectionError.notConnected }
      #expect(snapshot.device.id == newID)
      #expect(snapshot.device.radioID == saved.radioID)
      _ = try await snapshot.services.session.getTime()
      #expect(try await manager.persistenceStore.fetchPendingSends(radioID: snapshot.device.radioID).contains { $0.id == pending.id })
      #expect(snapshot.device.wifiConnectionMethod == wifi)
    } catch {
      await env.ble.releaseAllGates()
      _ = await connecting?.result
      _ = await superseding?.value
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `registration does not invent or infer Bluetooth methods from a WiFi observation`(existingBluetooth: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      let oldID = UUID()
      let newID = UUID()
      let wifi = ConnectionMethod.wifi(host: "saved.radio", port: 5123, displayName: "WiFi")
      let methods: [ConnectionMethod] = existingBluetooth
        ? [.bluetooth(peripheralUUID: oldID, displayName: "Bluetooth"), wifi] : [wifi]
      let saved = DeviceDTO.testDevice(id: oldID, isActive: false).copy { $0.connectionMethods = methods }
      try await env.manager.persistenceStore.saveDevice(saved)
      let observed = saved.copy { $0.id = newID }
      let registered = try await env.manager.persistenceStore.registerConnectionDevice(observed, bluetoothPeripheralID: nil)
      #expect(registered.connectionMethods == methods)
      #expect(registered.radioID == saved.radioID)
      #expect(!registered.isActive)
      #expect(registered.lastConnected == saved.lastConnected)
      let roundTrip = try JSONDecoder().decode(DeviceDTO.self, from: JSONEncoder().encode(registered))
      #expect(roundTrip.connectionMethods == methods)
      #expect(roundTrip.radioID == saved.radioID)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `authenticated Bluetooth registration does not create a method before selection`(hasWiFi: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      let oldID = UUID()
      let newID = UUID()
      let methods: [ConnectionMethod] = hasWiFi ? [.wifi(host: "saved.radio", port: 5123, displayName: "WiFi")] : []
      let saved = DeviceDTO.testDevice(id: oldID, isActive: false).copy { $0.connectionMethods = methods }
      try await env.manager.persistenceStore.saveDevice(saved)
      let observed = saved.copy { $0.id = newID }
      let registered = try await env.manager.persistenceStore.registerConnectionDevice(observed, bluetoothPeripheralID: newID)
      #expect(registered.connectionMethods == methods)
      #expect(registered.radioID == saved.radioID)
      #expect(!registered.isActive)
      #expect(registered.lastConnected == saved.lastConnected)
    } catch { await env.close(); throw error }
    await env.close()
  }

  private func seedPendingSend(store: PersistenceStore, radioID: UUID, radio: ConnectionTestRadioTransport) async throws -> PendingSendDTO {
    let contact = ContactDTO.testContact(radioID: radioID)
    try await store.saveContact(contact)
    let message = MessageDTO(from: Message(radioID: radioID, contactID: contact.id, text: "Queued before re-pair", timestamp: 1_700_000_000))
    try await store.saveMessage(message)
    let pending = PendingSendDTO(
      id: UUID(), radioID: radioID, messageID: message.id, kind: .dm, contactID: contact.id,
      channelIndex: nil, isResend: false, messageText: message.text, messageTimestamp: message.timestamp,
      localNodeName: "Field radio", sequence: 1, enqueuedAt: Date()
    )
    try await store.upsertPendingSend(pending)
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
    return pending
  }
}

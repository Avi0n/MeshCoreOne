import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Canonical identity partition consumers")
@MainActor
struct CanonicalIdentityConsumerTests {
  @Test
  func `actual channel room discovery and cleanup handlers use the canonical partition`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      harness.manager.testNotificationSystemOperations = .init(
        setBadgeCount: { _ in }, deliveredRequests: { [] }, removeDeliveredNotifications: { _ in }
      )
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      let store = harness.manager.persistenceStore
      let roomKey = Data(repeating: 0xD4, count: ProtocolLimits.publicKeySize)
      let roomA = RemoteNodeSessionDTO.testSession(radioID: harness.deviceA.radioID, publicKey: roomKey, name: "A room")
      let roomB = RemoteNodeSessionDTO.testSession(radioID: harness.deviceB.radioID, publicKey: roomKey, name: "B room")
      try await store.saveRemoteNodeSessionDTO(roomA)
      try await store.saveRemoteNodeSessionDTO(roomB)
      let contactA = ContactDTO.testContact(radioID: harness.deviceA.radioID, publicKey: roomKey, name: "A room", typeRawValue: ContactType.room.rawValue)
      let contactB = ContactDTO.testContact(radioID: harness.deviceB.radioID, publicKey: roomKey, name: "B room", typeRawValue: ContactType.room.rawValue)
      try await store.saveContact(contactA)
      try await store.saveContact(contactB)
      let pendingA = try await source.messageService.createPendingMessage(text: "Preserve A history", to: contactA)
      _ = try await source.messageService.createPendingMessage(text: "Delete B history", to: contactB)
      let advertKey = Data(repeating: 0xD6, count: ProtocolLimits.publicKeySize)
      let advertA = ContactDTO.testContact(radioID: harness.deviceA.radioID, publicKey: advertKey)
      let advertB = ContactDTO.testContact(radioID: harness.deviceB.radioID, publicKey: advertKey)
      try await store.saveContact(advertA)
      try await store.saveContact(advertB)
      _ = try await source.messageService.createPendingMessage(text: "Preserve A advert history", to: advertA)
      _ = try await source.messageService.createPendingMessage(text: "Delete B advert history", to: advertB)
      await harness.bluetooth.setResponsePackets(for: .getContacts, packets: [
        Data([ResponseCode.contactStart.rawValue, 2, 0, 0, 0]),
        Data([ResponseCode.contact.rawValue]) + contactData(key: roomKey, name: "B room", type: .room),
        Data([ResponseCode.contact.rawValue]) + contactData(key: advertKey, name: "B advertised", type: .chat),
        Data([ResponseCode.contactEnd.rawValue, 1, 0, 0, 0])
      ])
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      try await source.nodeConfigService.importConfig(
        MeshCoreNodeConfig(
          privateKey: CanonicalIdentityFixture.privateKeyHex,
          channels: [.init(name: "Canonical", secret: String(repeating: "CD", count: 16))]
        ), sections: ConfigSections(nodeIdentity: true, channels: true), radioID: harness.deviceA.radioID
      )
      let live = try #require(harness.manager.services)
      var channel = Data([ResponseCode.channelMessageReceived.rawValue, 0, 0, MeshCore.TextType.plainText.rawValue])
      channel.append(withUnsafeBytes(of: UInt32(1_700_000_100).littleEndian) { Data($0) })
      channel.append(Data("Remote: Canonical channel incoming".utf8))
      await harness.bluetooth.inject(channel)
      var room = Data([ResponseCode.contactMessageReceived.rawValue]) + roomKey.prefix(6)
      room.append(contentsOf: [0, MeshCore.TextType.signed.rawValue])
      room.append(withUnsafeBytes(of: UInt32(1_700_000_101).littleEndian) { Data($0) })
      room.append(contentsOf: [0xE1, 0xE2, 0xE3, 0xE4])
      room.append(Data("Author: Canonical room incoming".utf8))
      await harness.bluetooth.inject(room)
      let discoveredKey = Data(repeating: 0xD5, count: ProtocolLimits.publicKeySize)
      await harness.bluetooth.inject(newAdvertisement(key: discoveredKey))
      try await waitUntil("canonical channel message should persist") {
        await (try? store.fetchMessages(radioID: harness.deviceB.radioID, channelIndex: 0).contains { $0.text == "Canonical channel incoming" }) == true
      }
      try await waitUntil("canonical room should receive its signed message") {
        await (try? store.fetchRoomMessages(sessionID: roomB.id).contains { $0.text.contains("Canonical room incoming") }) == true
      }
      try await waitUntil("canonical discovered node should persist") {
        await (try? store.fetchDiscoveredNodes(radioID: harness.deviceB.radioID).contains { $0.publicKey == discoveredKey }) == true
      }
      #expect(try await store.fetchMessages(radioID: harness.deviceA.radioID, channelIndex: 0).isEmpty)
      #expect(try await store.fetchRoomMessages(sessionID: roomA.id).isEmpty)
      #expect(try await store.fetchDiscoveredNodes(radioID: harness.deviceA.radioID).contains { $0.publicKey == discoveredKey } == false)
      await harness.bluetooth.inject(Data([ResponseCode.contactDeleted.rawValue]) + advertKey)
      try await waitUntil("canonical advert deletion should delete B contact and history") {
        let contact = try? await store.fetchContact(id: advertB.id)
        let messages = try? await store.fetchMessages(contactID: advertB.id)
        return contact == nil && messages?.isEmpty == true
      }
      #expect(try await store.fetchContact(id: advertA.id) != nil)
      #expect(try await store.fetchMessages(contactID: advertA.id).count == 1)
      try await live.contactService.removeContact(radioID: harness.deviceB.radioID, publicKey: roomKey)
      #expect(try await store.fetchMessages(contactID: contactB.id).isEmpty)
      #expect(try await store.fetchContact(id: contactA.id)?.radioID == harness.deviceA.radioID)
      #expect(try await store.fetchRemoteNodeSession(id: roomA.id)?.radioID == harness.deviceA.radioID)
      #expect(try await store.fetchRemoteNodeSession(id: roomB.id) == nil)
      #expect(try await store.fetchMessage(id: pendingA.id)?.radioID == harness.deviceA.radioID)
      #expect(try await live.settingsService.getTime().timeIntervalSince1970 > 0)
    }
  }

  @Test
  func `parked canonical send hydrates on reconnect while old unsent data stays excluded`() async throws {
    try await ConnectionRadioHandoffHarness.withHarness { harness in
      try await CanonicalIdentityFixture.prepareGhost(in: harness)
      try await harness.connectBluetooth()
      let source = try #require(harness.manager.services)
      let oldMessage = try await source.messageService.createPendingChannelMessage(
        text: "A-unsent", channelIndex: 0, radioID: harness.deviceA.radioID
      )
      let oldEnvelope = envelope(oldMessage)
      _ = try await harness.manager.persistenceStore.insertPendingSendAssigningSequence(
        PendingSendDTO(envelope: oldEnvelope, radioID: harness.deviceA.radioID)
      )
      await harness.bluetooth.setPublicKey(CanonicalIdentityFixture.publicKey, for: harness.deviceA.id)
      await harness.bluetooth.setChannel(index: 0, name: "Canonical", secret: Data(repeating: 0xCD, count: 16))
      try await source.nodeConfigService.importConfig(
        MeshCoreNodeConfig(privateKey: CanonicalIdentityFixture.privateKeyHex),
        sections: ConfigSections(nodeIdentity: true), radioID: harness.deviceA.radioID
      )
      let canonical = try #require(harness.manager.services)
      let channel = try #require(try await canonical.channelService.getChannel(radioID: harness.deviceB.radioID, index: 0))
      try #require(channel.secret == Data(repeating: 0xCD, count: 16))
      #expect(await harness.bluetooth.channelMatches(index: 0, secret: channel.secret))
      let message = try await canonical.messageService.createPendingChannelMessage(
        text: "B-parked", channelIndex: 0, radioID: harness.deviceB.radioID
      )
      let failedSendsBefore = await harness.bluetooth.failedSendCount
      await harness.bluetooth.setSendError(MeshCoreError.notConnected, for: .sendChannelMessage)
      try await canonical.chatSendQueueService.enqueueChannel(envelope(message))
      try await waitUntil("canonical send should fail transiently and park before reconnect") {
        let rows = await (try? harness.manager.persistenceStore.fetchPendingSends(radioID: harness.deviceB.radioID)) ?? []
        let row = try? await harness.manager.persistenceStore.fetchMessage(id: message.id)
        let failedSends = await harness.bluetooth.failedSendCount
        let parkedDrains = await canonical.chatSendQueueService.parkedDrainCountForTesting
        return failedSends > failedSendsBefore && parkedDrains > 0 && row?.status == .pending &&
          rows.contains { $0.messageID == message.id && ($0.attemptCount ?? 0) > 0 }
      }
      await harness.bluetooth.setSendError(nil, for: .sendChannelMessage)
      _ = try await harness.manager.connect(to: harness.deviceA.id, forceReconnect: true)
      try await waitUntil("fresh canonical queue should replay and remove its pending row") {
        let row = try? await harness.manager.persistenceStore.fetchMessage(id: message.id)
        let pending = await (try? harness.manager.persistenceStore.fetchPendingSends(radioID: harness.deviceB.radioID)) ?? []
        return row?.status == .sent && !pending.contains { $0.messageID == message.id }
      }
      #expect(try await harness.manager.persistenceStore.fetchPendingSends(radioID: harness.deviceA.radioID).contains { $0.messageID == oldMessage.id })
      let sent = await harness.bluetooth.sentData.filter { $0.first == CommandCode.sendChannelMessage.rawValue }
      #expect(sent.contains { $0.range(of: Data("B-parked".utf8)) != nil })
      #expect(sent.contains { $0.range(of: Data("A-unsent".utf8)) != nil } == false)
      #expect(try await harness.manager.services?.settingsService.getTime() != nil)
    }
  }

  private func envelope(_ message: MessageDTO) -> ChannelMessageEnvelope {
    ChannelMessageEnvelope(messageID: message.id, channelIndex: 0, isResend: false,
                           messageText: message.text, messageTimestamp: message.timestamp, localNodeName: "Canonical radio")
  }

  private func newAdvertisement(key: Data) -> Data {
    Data([ResponseCode.newAdvertisement.rawValue]) + contactData(key: key, name: "Canonical discovered", type: .chat)
  }

  private func contactData(key: Data, name: String, type: ContactType) -> Data {
    let nameBytes = Data(name.utf8)
    var packet = key
    packet.append(contentsOf: [type.rawValue, 0, 0xFF])
    packet.append(Data(repeating: 0, count: 64))
    packet.append(nameBytes)
    packet.append(Data(repeating: 0, count: 32 - nameBytes.count))
    packet.append(Data(repeating: 0, count: 16))
    return packet
  }
}

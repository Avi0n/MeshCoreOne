import Foundation
@testable import MC1Services
import Testing

@Suite("Text Rules conversation ownership")
struct TextRulesOwnershipTests {
  @Test
  func `contact binding survives insert only rollback and later collision`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radio = DeviceDTO.testDevice()
    try await store.saveDevice(radio)
    let prefix = Data([1, 2, 3, 4, 5, 6])
    let contact = ContactDTO.testContact(radioID: radio.radioID, publicKey: prefix + Data(repeating: 1, count: 26))
    let collision = ContactDTO.testContact(radioID: radio.radioID, publicKey: prefix + Data(repeating: 2, count: 26))
    let drop = try makeDrop(radioID: radio.radioID, prefix: prefix)
    try await store.recordRecentlyBlockedMessage(drop)

    try await store.saveContact(contact)
    let bound = try await store.recentlyBlockedMessages().first
    #expect(bound?.contactID == contact.id)
    try await store.deleteContactIfUnreferenced(id: contact.id)
    #expect(try await store.fetchContact(id: contact.id) != nil)
    #expect(try await store.recentlyBlockedMessages().first?.contactID == contact.id)
    #expect(try await store.hasRecentlyBlockedMessage(drop.identity))

    try await store.saveContact(collision)
    try await store.deleteContactIfUnreferenced(id: collision.id)
    #expect(try await store.fetchContact(id: collision.id) == nil)
    #expect(try await store.recentlyBlockedMessages().first?.contactID == contact.id)

    try await store.deleteContact(id: contact.id)
    #expect(try await store.recentlyBlockedCount() == 0)
    #expect(try await store.fetchContact(id: contact.id) == nil)
  }

  @Test
  func `removing one of two prefix matches binds only the unbound dm`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radio = DeviceDTO.testDevice()
    try await store.saveDevice(radio)
    let prefix = Data([1, 2, 3, 4, 5, 6])
    let first = ContactDTO.testContact(radioID: radio.radioID, publicKey: prefix + Data(repeating: 1, count: 26))
    let second = ContactDTO.testContact(radioID: radio.radioID, publicKey: prefix + Data(repeating: 2, count: 26))
    let room = RemoteNodeSessionDTO.testSession(radioID: radio.radioID)
    try await store.saveContact(first)
    try await store.saveContact(second)
    try await store.saveRemoteNodeSessionDTO(room)
    let roomID = try #require(await store.fetchRemoteNodeSession(id: room.id)?.textRulesID)
    let dmDrop = try makeDrop(radioID: radio.radioID, prefix: prefix)
    let roomDrop = try makeDrop(radioID: radio.radioID, prefix: Data(prefix.prefix(4)), conversationID: roomID)
    try await store.recordRecentlyBlockedMessage(dmDrop)
    try await store.recordRecentlyBlockedMessage(roomDrop)

    try await store.deleteContact(id: first.id)
    let retained = try await store.recentlyBlockedMessages()
    #expect(retained.first(where: { $0.id == dmDrop.id })?.contactID == second.id)
    #expect(retained.first(where: { $0.id == roomDrop.id })?.contactID == nil)

    try await store.deleteContact(id: second.id)
    #expect(try await store.recentlyBlockedMessages().map(\.id) == [roomDrop.id])
    try await store.deleteRemoteNodeSession(id: room.id)
    #expect(try await store.recentlyBlockedCount() == 0)
  }

  @Test
  func `restoring the same contact UUID does not restore its selection identity or override`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radio = DeviceDTO.testDevice()
    let contact = ContactDTO.testContact(radioID: radio.radioID)
    try await store.saveDevice(radio)
    try await store.saveContact(contact)
    let selected = try #require(await store.textRulesConversations(kind: .directMessage).first)
    try await store.setTextRulesOverride(.on, kind: selected.kind, key: selected.key)
    let backupService = AppBackupService()
    let backup = try await backupService.export(persistenceStore: store)
    let envelope = try parseBackup(data: backup.data)
    let exportedContact = try #require(envelope.contacts.first)
    #expect(exportedContact.id == contact.id)

    _ = try await backupService.importBackup(envelope: envelope, into: store)
    #expect(try await store.textRulesConversation(kind: selected.kind, key: selected.key)?.override == .on)
    try await store.deleteContact(id: contact.id)
    _ = try await backupService.importBackup(envelope: envelope, into: store)

    let restored = try #require(await store.fetchContact(id: contact.id))
    #expect(restored.textRulesID != selected.key.conversationID)
    #expect(restored.textRulesOverride == .inherit)
    #expect(try await store.textRulesConversation(kind: selected.kind, key: selected.key) == nil)
    await #expect(throws: PersistenceStoreError.self) {
      try await store.setTextRulesOverride(.on, kind: selected.kind, key: selected.key)
    }
  }

  @Test(arguments: [false, true])
  func `ghost reconciliation retires only an abandoned partition`(stillOwned: Bool) async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let current = DeviceDTO.testDevice(publicKey: Data(repeating: 1, count: 32))
    let ghost = DeviceDTO.testDevice(publicKey: Data(repeating: 2, count: 32))
    try await store.saveDevice(current)
    try await store.saveDevice(ghost)
    try await store.demoteDeviceToGhost(id: ghost.id)
    if stillOwned {
      try await store.saveDevice(.testDevice(radioID: current.radioID, publicKey: Data(repeating: 3, count: 32)))
    }
    let contact = ContactDTO.testContact(radioID: current.radioID)
    try await store.saveContact(contact)
    let before = try #require(await store.fetchContact(id: contact.id)?.textRulesID)
    let scope = try #require(await store.captureReceiveScope(radioID: current.radioID, link: .init()))
    let currentDrop = try makeDrop(radioID: current.radioID, prefix: nil)
    let retainedDrop = try makeDrop(radioID: ghost.radioID, prefix: nil)
    try await store.recordRecentlyBlockedMessage(currentDrop)
    try await store.recordRecentlyBlockedMessage(retainedDrop)

    #expect(try await store.reconcileGhostIdentity(currentDeviceID: current.id, newPublicKey: ghost.publicKey) == ghost.radioID)
    #expect(scope.isCurrent == stillOwned)
    #expect(try await store.fetchContact(id: contact.id)?.textRulesID == (stillOwned ? before : nil))
    #expect(try await store.hasRecentlyBlockedMessage(currentDrop.identity) == stillOwned)
    #expect(try await store.hasRecentlyBlockedMessage(retainedDrop.identity))
  }

  @Test
  func `completing a pending erase preserves a radio that tried to adopt its identity`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let current = DeviceDTO.testDevice(publicKey: Data(repeating: 1, count: 32))
    let target = DeviceDTO.testDevice(publicKey: Data(repeating: 2, count: 32), isActive: false).copy {
      $0.connectionMethods = []
    }
    try await store.saveDevice(current)
    try await store.saveDevice(target)
    _ = try await store.beginRadioErase(target)

    await #expect(throws: PersistenceStoreError.self) {
      _ = try await store.reconcileGhostIdentity(currentDeviceID: current.id, newPublicKey: target.publicKey)
    }
    try await store.completeRadioErase(radioID: target.radioID)

    let surviving = try #require(await store.fetchDevice(id: current.id))
    #expect(surviving.radioID == current.radioID)
    #expect(surviving.publicKey == current.publicKey)
    #expect(try await store.fetchDevice(id: target.id) == nil)
  }

  private func makeDrop(
    radioID: UUID,
    prefix: Data?,
    conversationID: UUID? = nil
  ) throws -> RecentlyBlockedMessageDTO {
    let body = "Keep this original"
    var preference = TextRulesPreference()
    preference.append(.init(phrase: "original", lookIn: .message, match: .contains))
    return try RecentlyBlockedMessageDTO(
      identity: .init(
        radioID: radioID, conversationID: conversationID, senderPrefix: prefix,
        senderTimestamp: 1, textType: 0, wireText: Data(body.utf8)
      ),
      arrivedAt: Date(), body: body, sender: nil, conversationName: nil, radioName: nil,
      match: #require(preference.firstMatch(body: body, sender: nil))
    )
  }
}

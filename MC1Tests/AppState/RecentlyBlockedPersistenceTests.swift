import Foundation
@testable import MC1Services
import SwiftData
import Testing

@Suite("Recently Blocked persistence")
struct RecentlyBlockedPersistenceTests {
  private struct InjectedSaveFailure: Error {}

  @Test
  func `retained drops are capped across radios and rollback preserves the committed archive`() async throws {
    let directory = URL.temporaryDirectory.appending(path: UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let configuration = ModelConfiguration(
      schema: PersistenceStore.schema,
      url: directory.appending(path: "recently-blocked.store")
    )
    let radios = [UUID(), UUID()]
    let limit = PersistenceStore.recentlyBlockedRetentionLimit
    let drops = try (0...limit + 1).map { index in
      try makeDrop(index: index, radioID: radios[index % radios.count])
    }

    do {
      let container = try ModelContainer(for: PersistenceStore.schema, configurations: [configuration])
      let store = PersistenceStore(modelContainer: container)
      for drop in drops.dropLast() {
        try await store.recordRecentlyBlockedMessage(drop)
      }
      let lastDrop = drops[limit]
      try await store.recordRecentlyBlockedMessage(lastDrop)
      let committed = try await store.recentlyBlockedMessages()
      #expect(committed == Array(drops[1...limit].reversed()))

      await store.setRecordRecentlyBlockedFaultInjection { throw InjectedSaveFailure() }
      await #expect(throws: InjectedSaveFailure.self) {
        try await store.recordRecentlyBlockedMessage(drops[limit + 1])
      }
      #expect(try await store.recentlyBlockedMessages() == committed)
    }

    let reopened = try ModelContainer(for: PersistenceStore.schema, configurations: [configuration])
    let store = PersistenceStore(modelContainer: reopened)
    #expect(try await store.recentlyBlockedMessages() == Array(drops[1...limit].reversed()))
    let exported = try await AppBackupService().export(persistenceStore: store)
    let envelope = try parseBackup(data: exported.data)
    #expect(envelope.messages.isEmpty)
    #expect(envelope.roomMessages.isEmpty)
    let encoded = try JSONEncoder().encode(envelope)
    #expect(!String(decoding: encoded, as: UTF8.self).contains("Archive-only body"))
  }

  @Test(arguments: [false, true])
  func `restored DM wins over retained drop after reopening the store`(corrected: Bool) async throws {
    let directory = URL.temporaryDirectory.appending(path: UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let configuration = ModelConfiguration(
      schema: PersistenceStore.schema,
      url: directory.appending(path: "dm-replay.store")
    )
    let radioID = UUID()
    let radioKey = Data(repeating: 0x17, count: 32)
    let contactKey = Data(repeating: 0x48, count: 32)
    let contact = ContactDTO(from: Contact(
      radioID: radioID, publicKey: contactKey, name: "Peer", lastHeardTimestamp: 0
    ))
    let arrival = MessageDTO(from: Message(
      radioID: radioID, contactID: contact.id, text: "Field alert ", timestamp: 1_700_000_100,
      directionRawValue: MessageDirection.incoming.rawValue, statusRawValue: MessageStatus.delivered.rawValue,
      senderKeyPrefix: Data(contactKey.prefix(6)), deduplicationKey: "live-contact-key",
      timestampCorrected: corrected, senderTimestamp: corrected ? 50 : nil
    ))
    var preference = TextRulesPreference()
    preference.setSelection(.all, for: .directMessage)
    preference.append(.init(phrase: "alert", lookIn: .message, match: .contains))

    do {
      let store = try PersistenceStore(modelContainer: ModelContainer(
        for: PersistenceStore.schema, configurations: [configuration]
      ))
      try await store.saveDevice(DeviceDTO(from: Device(radioID: radioID, publicKey: radioKey, nodeName: "Local")))
      try await store.saveContact(contact)
      let scope = try #require(await store.captureReceiveScope(radioID: radioID, link: .init()))
      let captured = try #require(await store.fetchContact(id: contact.id))
      let admission = try await store.admitDirectMessage(
        arrival, wireTextType: arrival.textType.rawValue, contact: captured, scope: scope, preference: preference
      )
      guard case .discarded = admission else {
        Issue.record("Expected rule to drop the initial arrival")
        return
      }
      #expect(try await store.recentlyBlockedCount() == 1)
    }

    let store = try PersistenceStore(modelContainer: ModelContainer(
      for: PersistenceStore.schema, configurations: [configuration]
    ))
    let foreignRadioID = UUID()
    let foreignContact = ContactDTO(from: Contact(
      radioID: foreignRadioID, publicKey: contactKey, name: "Peer", lastHeardTimestamp: 0
    ))
    let foreignMessage = arrival.copy {
      $0.id = UUID()
      $0.radioID = foreignRadioID
      $0.contactID = foreignContact.id
      $0.deduplicationKey = "foreign-contact-key"
    }
    let backup = AppBackupEnvelope(
      appVersion: "1", appBuild: "1",
      devices: [DeviceDTO(from: Device(radioID: foreignRadioID, publicKey: radioKey, nodeName: "Foreign"))],
      contacts: [foreignContact],
      messages: [foreignMessage]
    )
    _ = try await AppBackupService().importBackup(envelope: backup, into: store)
    let scope = try #require(await store.captureReceiveScope(radioID: radioID, link: .init()))
    let captured = try #require(await store.fetchContact(id: contact.id))
    let admission = try await store.admitDirectMessage(
      arrival, wireTextType: arrival.textType.rawValue, contact: captured, scope: scope, preference: preference
    )
    guard case let .replay(saved) = admission else {
      Issue.record("Expected exact saved DM to win over the retained drop")
      return
    }
    #expect(saved.id == foreignMessage.id)
    #expect(saved.radioID == radioID)
    #expect(saved.contactID == contact.id)
    #expect(try await store.recentlyBlockedCount() == 1)
    #expect(try await store.fetchMessages(contactID: contact.id).count == 1)
  }

  private func makeDrop(index: Int, radioID: UUID) throws -> RecentlyBlockedMessageDTO {
    var preference = TextRulesPreference()
    preference.append(.init(phrase: "archive", lookIn: .messageOrSender, match: .contains))
    let body = "Archive-only body \(index) "
    let sender = "Archive Sender"
    let match = try #require(preference.firstMatch(body: body, sender: sender))
    return RecentlyBlockedMessageDTO(
      identity: .init(
        radioID: radioID,
        conversationID: UUID(),
        senderPrefix: nil,
        senderTimestamp: UInt32(index),
        textType: 0,
        wireText: Data(body.utf8)
      ),
      arrivedAt: Date(timeIntervalSince1970: Double(index)),
      body: body,
      sender: sender,
      conversationName: "#test",
      radioName: "Radio \(radioID)",
      match: match
    )
  }
}

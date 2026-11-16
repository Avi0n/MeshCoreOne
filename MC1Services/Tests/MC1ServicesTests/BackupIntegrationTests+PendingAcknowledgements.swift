import Foundation
@testable import MC1Services
import Testing

extension BackupIntegrationTests {
  @Test
  func `ACK ownership stays local while the existing envelope round trips message history`() async throws {
    let radioID = UUID()
    let source = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let contact = ContactDTO.testContact(radioID: radioID)
    try await source.saveContact(contact)
    let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id, ackCode: 123)
    try await source.saveMessage(message)
    let sentAt = Date()
    let owner = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id, isSingleAttempt: true), radioID: radioID,
      ackCodes: [Data([1, 2, 3, 4])], ackSentAt: sentAt, ackDeadline: sentAt.addingTimeInterval(60)
    )
    #expect(try await source.savePendingAcknowledgement(owner, replacingPendingSendID: nil))
    let service = AppBackupService()
    let exported = try await service.export(persistenceStore: source)
    let envelope = try parseBackup(data: exported.data)
    let json = try String(decoding: makeBackupJSONEncoder().encode(envelope), as: UTF8.self)
    #expect(!json.contains("ackCodes"))
    #expect(!json.contains("ackSentAt"))
    #expect(!json.contains("ackDeadline"))
    #expect(!json.contains("pendingSends"))
    #expect(!json.contains("isSingleAttempt"))
    #expect(envelope.messages.first?.id == message.id)
    #expect(envelope.messages.first?.ackCode == 123)
    let destination = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    _ = try await service.importBackup(envelope: envelope, into: destination)
    #expect(try await destination.fetchMessage(id: message.id)?.ackCode == 123)
    #expect(try await destination.fetchPendingSends(radioID: radioID).isEmpty)
    #expect(try await source.fetchPendingSends(radioID: radioID).first?.ackCodes == owner.ackCodes)
    #expect(try await source.fetchPendingSends(radioID: radioID).first?.isSingleAttempt == true)
  }
}

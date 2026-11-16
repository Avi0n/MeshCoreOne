import Foundation
@testable import MC1Services
import Testing

@Suite("Pending acknowledgement persistence")
struct PendingAcknowledgementPersistenceTests {
  @Test(arguments: [MessageStatus.delivered, .failed])
  func `a stale ACK owner cannot resolve a replacement manual retry`(status: MessageStatus) async throws {
    let fixture = try await makeFixture()
    let waiting = acknowledgement(for: fixture.row)
    #expect(try await fixture.store.savePendingAcknowledgement(waiting, replacingPendingSendID: fixture.row.id))
    let replacement = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: fixture.message.id, contactID: fixture.contact.id),
      radioID: fixture.row.radioID
    )
    _ = try await fixture.store.replacePendingSendForRetry(messageID: fixture.message.id, dto: replacement)
    #expect(try await fixture.store.resolvePendingAcknowledgement(
      id: waiting.id, expectedDeadline: waiting.ackDeadline,
      status: status, ackCode: 123, roundTripTime: nil
    ) == false)
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == .pending)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.row.radioID).map(\.id) == [replacement.id])
  }

  @Test
  func `handoff requires the original ordinary row and cannot convert a replacement`() async throws {
    let fixture = try await makeFixture()
    let replacement = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: fixture.message.id, contactID: fixture.contact.id),
      radioID: fixture.row.radioID
    )
    _ = try await fixture.store.replacePendingSendForRetry(messageID: fixture.message.id, dto: replacement)
    #expect(try await fixture.store.savePendingAcknowledgement(
      acknowledgement(for: fixture.row), replacingPendingSendID: fixture.row.id
    ) == false)
    let row = try #require(await fixture.store.fetchPendingSends(radioID: fixture.row.radioID).first)
    #expect(row.id == replacement.id)
    #expect(row.ackDeadline == nil)
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == .pending)
  }

  @Test
  func `a changed phase with the same row id cannot be expired by an old owner`() async throws {
    let fixture = try await makeFixture()
    let waiting = acknowledgement(for: fixture.row)
    #expect(try await fixture.store.savePendingAcknowledgement(waiting, replacingPendingSendID: fixture.row.id))
    try await fixture.store.upsertPendingSend(fixture.row)
    #expect(try await fixture.store.resolvePendingAcknowledgement(
      id: waiting.id, expectedDeadline: waiting.ackDeadline,
      status: .failed, ackCode: nil, roundTripTime: nil
    ) == false)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.row.radioID).first?.ackDeadline == nil)
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == .sent)
  }

  @Test
  func `expiry preserves a delivered row and removes only its old owner`() async throws {
    let fixture = try await makeFixture()
    let waiting = acknowledgement(for: fixture.row)
    #expect(try await fixture.store.savePendingAcknowledgement(waiting, replacingPendingSendID: fixture.row.id))
    try await fixture.store.updateMessageAck(id: fixture.message.id, ackCode: 123, status: .delivered, roundTripTime: 456)
    #expect(try await fixture.store.resolvePendingAcknowledgement(
      id: waiting.id, expectedDeadline: waiting.ackDeadline,
      status: .failed, ackCode: nil, roundTripTime: nil
    ) == false)
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == .delivered)
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.roundTripTime == 456)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.row.radioID).isEmpty)
  }

  @Test(arguments: [false, true])
  func `restored expiry uses the original absolute deadline across configuration changes`(future: Bool) async throws {
    let fixture = try await makeFixture()
    let now = Date()
    let sentAt = now.addingTimeInterval(-120)
    let deadline = now.addingTimeInterval(future ? 120 : -1)
    let waiting = acknowledgement(for: fixture.row, sentAt: sentAt, deadline: deadline)
    #expect(try await fixture.store.savePendingAcknowledgement(waiting, replacingPendingSendID: fixture.row.id))
    let service = MessageService(
      session: MockMeshCoreSession(), dataStore: fixture.store, contactService: nil,
      config: MessageServiceConfig(ackGiveUpWindow: future ? 0 : 1000)
    )
    try await service.restorePendingAcknowledgements(radioID: fixture.row.radioID)
    try await service.checkExpiredAcks()
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == (future ? .sent : .failed))
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.row.radioID).isEmpty == !future)
  }

  @Test
  func `another Radio ID cannot adopt or resolve an ACK owner`() async throws {
    let fixture = try await makeFixture()
    let waiting = acknowledgement(for: fixture.row)
    #expect(try await fixture.store.savePendingAcknowledgement(waiting, replacingPendingSendID: fixture.row.id))
    let service = MessageService(session: MockMeshCoreSession(), dataStore: fixture.store, contactService: nil)
    try await service.restorePendingAcknowledgements(radioID: UUID())
    await service.handleAcknowledgement(code: Data([1, 2, 3, 4]), tripTime: 1)
    #expect(await service.pendingAckCount == 0)
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == .sent)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.row.radioID).count == 1)
  }

  @Test
  func `ordinary rows default to replay while an inline ACK owner has a new sequence and zero attempts`() async throws {
    let fixture = try await makeFixture()
    #expect(fixture.row.ackCodes == nil)
    #expect(fixture.row.ackSentAt == nil)
    #expect(fixture.row.ackDeadline == nil)
    #expect(fixture.row.directMessageEnvelope() != nil)
    let inlineMessage = MessageDTO.testDirectMessage(radioID: fixture.row.radioID, contactID: fixture.contact.id)
    try await fixture.store.saveMessage(inlineMessage)
    let sentAt = Date()
    let owner = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: inlineMessage.id, contactID: fixture.contact.id), radioID: fixture.row.radioID,
      ackCodes: [Data([1, 2, 3, 4])], ackSentAt: sentAt, ackDeadline: sentAt.addingTimeInterval(60)
    )
    #expect(try await fixture.store.savePendingAcknowledgement(owner, replacingPendingSendID: nil))
    let saved = try #require(await fixture.store.fetchPendingSends(radioID: fixture.row.radioID).last)
    #expect(saved.sequence > fixture.row.sequence)
    #expect(saved.attemptCount == 0)
    #expect(saved.directMessageEnvelope() == nil)
  }

  @Test
  func `an already delivered message drops its owner without a new status`() async throws {
    let live = try await serviceRestoringOwner()
    try await live.store.updateMessageAck(id: live.message.id, ackCode: 111, status: .delivered, roundTripTime: 222)
    let events = live.service.statusEvents()
    await live.service.handleAcknowledgement(code: Self.ackCode, tripTime: 999)
    #expect(try await live.store.fetchMessage(id: live.message.id)?.status == .delivered)
    #expect(try await live.store.fetchMessage(id: live.message.id)?.roundTripTime == 222)
    #expect(try await live.store.fetchPendingSends(radioID: live.contact.radioID).isEmpty)
    #expect(await live.service.pendingAckCount == 0)
    let recorded = await live.service.drainStatusEvents(events)
    #expect(recorded.resolvedIDs.isEmpty)
    #expect(recorded.failedIDs.isEmpty)
  }

  @Test
  func `an already failed message drops its owner without a new status`() async throws {
    let live = try await serviceRestoringOwner()
    try await live.store.updateMessageStatus(id: live.message.id, status: .failed)
    let events = live.service.statusEvents()
    try await live.service.checkExpiredAcks()
    #expect(try await live.store.fetchMessage(id: live.message.id)?.status == .failed)
    #expect(try await live.store.fetchPendingSends(radioID: live.contact.radioID).isEmpty)
    #expect(await live.service.pendingAckCount == 0)
    let recorded = await live.service.drainStatusEvents(events)
    #expect(recorded.resolvedIDs.isEmpty)
    #expect(recorded.failedIDs.isEmpty)
  }

  @Test
  func `a missing message drops its owner without a new status`() async throws {
    let live = try await serviceRestoringOwner()
    let owner = try #require(await live.store.fetchPendingSends(radioID: live.contact.radioID).first)
    try await live.store.deleteMessage(id: live.message.id)
    try await live.store.upsertPendingSend(owner)
    let saved = try #require(await live.store.fetchPendingSends(radioID: live.contact.radioID).first)
    try await live.service.setPendingAckForTest(PendingAck(
      messageID: live.message.id,
      contactID: live.contact.id,
      ackCodes: Set(saved.ackCodes ?? []),
      sentAt: saved.ackSentAt ?? Date(),
      timeout: 1,
      owner: .init(id: saved.id, radioID: saved.radioID, deadline: #require(saved.ackDeadline), phase: .saved)
    ))
    let events = live.service.statusEvents()
    await live.service.handleAcknowledgement(code: Self.ackCode, tripTime: 9)
    #expect(try await live.store.fetchMessage(id: live.message.id) == nil)
    #expect(try await live.store.fetchPendingSends(radioID: live.contact.radioID).isEmpty)
    #expect(await live.service.pendingAckCount == 0)
    let recorded = await live.service.drainStatusEvents(events)
    #expect(recorded.resolvedIDs.isEmpty)
    #expect(recorded.failedIDs.isEmpty)
  }

  private static let ackCode = Data([1, 2, 3, 4])

  private func serviceRestoringOwner() async throws -> (
    store: PersistenceStore, contact: ContactDTO, message: MessageDTO, service: MessageService
  ) {
    let fixture = try await makeFixture()
    let sentAt = Date().addingTimeInterval(-60)
    let waiting = acknowledgement(for: fixture.row, sentAt: sentAt)
    #expect(try await fixture.store.savePendingAcknowledgement(waiting, replacingPendingSendID: fixture.row.id))
    let service = MessageService(session: MockMeshCoreSession(), dataStore: fixture.store, contactService: nil)
    try await service.restorePendingAcknowledgements(radioID: fixture.row.radioID)
    #expect(await service.pendingAckCount == 1)
    return (fixture.store, fixture.contact, fixture.message, service)
  }

  private func makeFixture() async throws -> (
    store: PersistenceStore, contact: ContactDTO, message: MessageDTO, row: PendingSendDTO
  ) {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let contact = ContactDTO.testContact(radioID: radioID)
    try await store.saveDevice(.testDevice(radioID: radioID))
    try await store.saveContact(contact)
    let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: contact.id, status: .pending)
    try await store.saveMessage(message)
    let row = PendingSendDTO(envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id), radioID: radioID)
    try await store.upsertPendingSend(row)
    return (store, contact, message, row)
  }

  private func acknowledgement(
    for row: PendingSendDTO, sentAt: Date = Date(), deadline: Date? = nil
  ) -> PendingSendDTO {
    PendingSendDTO(
      id: row.id, envelope: DirectMessageEnvelope(messageID: row.messageID, contactID: row.contactID!),
      radioID: row.radioID, enqueuedAt: row.enqueuedAt, ackCodes: [Data([1, 2, 3, 4])],
      ackSentAt: sentAt, ackDeadline: deadline ?? sentAt.addingTimeInterval(30)
    )
  }
}

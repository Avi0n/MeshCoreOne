import Foundation
@testable import MC1Services
import MeshCore
import SwiftData
import Testing

@Suite("Pending send attempt preparation")
@MainActor
struct PendingSendAttemptPreparationTests {
  @Test
  func `failed preparation rolls back status and attempt count while preserving buffered RX entries`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let contact = ContactDTO.testContact()
    try await store.saveContact(contact)
    let message = MessageDTO.testDirectMessage(radioID: contact.radioID, contactID: contact.id, status: .failed)
    try await store.saveMessage(message)
    let pending = PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id), radioID: contact.radioID
    )
    _ = try await store.insertPendingSendAssigningSequence(pending)
    let entry = RxLogEntryDTO(
      radioID: contact.radioID,
      from: ParsedRxLogData(
        snr: 10, rssi: -65, rawPayload: Data([0x15]), routeType: .flood,
        payloadType: .groupText, payloadVersion: 0, payloadTypeBits: 5, transportCode: nil,
        pathLength: 0, pathNodes: [], packetPayload: Data([0xAB])
      )
    )
    try await store.bufferRxLogEntryForAttemptPreparation(entry)
    #expect(await store.unsavedRxLogInsertCount == 1)
    await store.setPendingSendAttemptSaveFaultInjection { _ in throw CocoaError(.fileWriteUnknown) }

    await #expect(throws: CocoaError.self) {
      _ = try await store.preparePendingSendAttempt(messageID: message.id)
    }

    #expect(try await store.fetchMessage(id: message.id)?.status == .failed)
    #expect(try await store.fetchPendingSends(radioID: contact.radioID).first?.attemptCount == 0)
    #expect(await store.unsavedRxLogInsertCount == 0)
    let persisted = ModelContext(store.modelContainer)
    #expect(try persisted.fetch(FetchDescriptor<Message>()).first?.status == .failed)
    #expect(try persisted.fetch(FetchDescriptor<PendingSend>()).first?.attemptCount == 0)
    #expect(try persisted.fetch(FetchDescriptor<RxLogEntry>()).map(\.id) == [entry.id])

    await store.setPendingSendAttemptSaveFaultInjection(nil)
    let prepared = try #require(try await store.preparePendingSendAttempt(messageID: message.id))
    #expect(prepared.pendingSendID == pending.id)
    #expect(prepared.attemptCount == 1)
    #expect(try await store.fetchMessage(id: message.id)?.status == .pending)
    #expect(try await store.fetchPendingSends(radioID: contact.radioID).first?.attemptCount == 1)
    #expect(try await store.fetchRxLogEntries(radioID: contact.radioID).map(\.id) == [entry.id])
  }

  @Test
  func `preparing a first attempt preserves delivery recorded before the drain`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let message = MessageDTO.testDirectMessage(status: .delivered)
    try await store.saveMessage(message)
    let pending = try PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: message.id, contactID: #require(message.contactID)),
      radioID: message.radioID
    )
    _ = try await store.insertPendingSendAssigningSequence(pending)

    _ = try await store.preparePendingSendAttempt(messageID: message.id)

    #expect(try await store.fetchMessage(id: message.id)?.status == .delivered)
  }
}

private extension PersistenceStore {
  func bufferRxLogEntryForAttemptPreparation(_ entry: RxLogEntryDTO) throws {
    try saveRxLogEntry(entry)
    // Keep the partial batch uncommitted until preparation owns the save.
    rxLogFlushTask?.cancel()
    rxLogFlushTask = nil
  }
}

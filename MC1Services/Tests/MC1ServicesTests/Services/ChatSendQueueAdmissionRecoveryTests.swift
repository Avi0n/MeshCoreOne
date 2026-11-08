import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Initial queued send reconnect recovery", .timeLimit(.minutes(1)))
@MainActor
struct ChatSendQueueAdmissionRecoveryTests {
  private static let acknowledgementTimeoutMilliseconds: UInt32 = 30000

  @Test
  func `a replacement queue records delivery after retirement interrupted initial admission`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radio = ConnectionTestRadioTransport()
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    let contact = ContactDTO.testContact()
    try await store.saveContact(contact)
    await radio.setContact(contact.toContactFrame().toMeshContact())
    let original = makeQueue(store: store, session: session, radioID: contact.radioID)
    await store.setPendingSendInsertHandler {
      Task { await original.shutdown() }
      try await waitUntil("retirement should interrupt the pending-send write") { original.isRetiredForTesting }
    }

    let message: MessageDTO
    do {
      let admission = try await original.enqueueDirectMessage(text: "Recover initial admission", to: contact)
      await original.shutdown()
      guard case let .enqueueFailed(saved, _) = admission else {
        Issue.record("Expected retirement after Message creation to report an enqueue failure")
        await session.stop()
        return
      }
      message = saved
      #expect(try await store.fetchMessage(id: message.id)?.status == .failed)
      #expect(try await store.fetchPendingSends(radioID: contact.radioID).first?.attemptCount == 0)
    } catch {
      await original.shutdown()
      await session.stop()
      throw error
    }

    await store.setPendingSendInsertHandler(nil)
    let messageService = makeMessageService(store: store, session: session)
    await messageService.startEventMonitoring()
    let replacement = makeQueue(store: store, session: session, radioID: contact.radioID, messageService: messageService)
    do {
      await radio.holdSubmissions(for: .sendMessage)
      await replacement.hydrate()
      try await waitUntil("the replacement queue should submit the retained send") {
        await radio.submissionIsHeld(for: .sendMessage)
      }
      let submitted = try #require(try await store.fetchMessage(id: message.id))
      let acknowledgement = AckCodeBuilder.expectedAck(
        timestamp: submitted.timestamp, attempt: 0, text: submitted.text, senderPublicKey: radio.publicKey
      )
      var accepted = Data([ResponseCode.messageSent.rawValue, 0]) + acknowledgement
      withUnsafeBytes(of: Self.acknowledgementTimeoutMilliseconds.littleEndian) { accepted.append(contentsOf: $0) }
      await radio.setResponsePackets(for: .sendMessage, packets: [accepted, Data([ResponseCode.ack.rawValue]) + acknowledgement])
      await radio.releaseSubmissions(for: .sendMessage)
      await replacement.awaitDrainCompletion()

      let resolved = try await store.fetchMessage(id: message.id)
      #expect(resolved?.status == .delivered)
      #expect(try await !store.hasPendingSend(messageID: message.id))
      #expect(await radio.sentData.filter { $0.first == CommandCode.sendMessage.rawValue }.count == 1)
    } catch {
      await radio.releaseAllGates()
      await replacement.shutdown()
      await messageService.stopEventMonitoring()
      await session.stop()
      throw error
    }
    await replacement.shutdown()
    await messageService.stopEventMonitoring()
    await session.stop()
  }

  private func makeMessageService(store: PersistenceStore, session: MeshCoreSession) -> MessageService {
    MessageService(
      session: session, dataStore: store, contactService: nil,
      config: MessageServiceConfig(triggerPathDiscoveryAfterFlood: false),
      dmSendBudget: { .singleAttempt }
    )
  }

  private func makeQueue(
    store: PersistenceStore, session: MeshCoreSession, radioID: UUID, messageService: MessageService? = nil
  ) -> ChatSendQueueService {
    ChatSendQueueService(
      radioID: radioID, dataStore: store,
      messageService: messageService ?? makeMessageService(store: store, session: session),
      channelService: ChannelService(session: session, dataStore: store, rxLogService: nil),
      reactionService: ReactionService()
    )
  }
}

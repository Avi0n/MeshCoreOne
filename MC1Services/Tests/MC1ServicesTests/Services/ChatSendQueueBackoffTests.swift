import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("ChatSendQueueService backoff rounds")
@MainActor
struct ChatSendQueueBackoffTests {
  private struct Harness {
    let queue: ChatSendQueueService
    let messageService: MessageService
    let session: MockMeshCoreSession
    let store: PersistenceStore
    let messageID: UUID
    let radioID: UUID
    let pinnedTimestamp: UInt32
  }

  private func makeHarness(
    maxBackoffRounds: Int,
    notBefore: Date? = nil,
    backoffRound: Int = 0
  ) async throws -> Harness {
    let device = Device(publicKey: Data(repeating: 1, count: 32), nodeName: "Test Device")
    let container = try PersistenceStore.createContainer(inMemory: true)
    container.mainContext.insert(device)
    try container.mainContext.save()
    let store = PersistenceStore(modelContainer: container)
    let radioID = device.radioID
    let contact = Contact(
      radioID: radioID, publicKey: Data(repeating: 2, count: 32), name: "Test Contact", lastHeardTimestamp: 0
    )
    container.mainContext.insert(contact)
    try container.mainContext.save()
    let contactDTO = try #require(try await store.fetchContact(id: contact.id))

    let session = MockMeshCoreSession()
    await session.setCurrentSelfInfo(.testSelfInfo())
    await session.setSendMessageResult(.success(
      MessageSentInfo(route: 0, expectedAck: Data([0x01, 0x02, 0x03, 0x04]), suggestedTimeoutMs: 10)
    ))
    let messageService = MessageService(
      session: session, dataStore: store, contactService: nil,
      config: MessageServiceConfig(ackGiveUpWindow: 0)
    )
    await messageService.setBackoffPolicy(DMBackoffPolicy(baseInterval: 0.2))
    let pending = try await messageService.createPendingMessage(text: "Hello", to: contactDTO)
    let pinnedTimestamp = UInt32(Date().addingTimeInterval(-3600).timeIntervalSince1970)
    try await store.updateMessageTimestamp(id: pending.id, timestamp: pinnedTimestamp)

    try await store.upsertPendingSend(PendingSendDTO(
      id: UUID(), radioID: radioID, messageID: pending.id, kind: .dm, contactID: contactDTO.id,
      channelIndex: nil, isResend: false, messageText: "", messageTimestamp: 0, localNodeName: nil,
      sequence: 1, enqueuedAt: Date(), attemptCount: notBefore == nil ? 0 : 1,
      isSingleAttempt: true,
      maxBackoffRounds: maxBackoffRounds, backoffRound: backoffRound, notBefore: notBefore
    ))
    let queue = ChatSendQueueService(
      radioID: radioID, dataStore: store, messageService: messageService,
      channelService: ChannelService(session: session, dataStore: store, rxLogService: nil),
      reactionService: ReactionService()
    )
    return Harness(
      queue: queue, messageService: messageService, session: session, store: store,
      messageID: pending.id, radioID: radioID, pinnedTimestamp: pinnedTimestamp
    )
  }

  private func waitUntil(_ what: String, _ condition: () async -> Bool) async throws {
    for _ in 0..<200 {
      if await condition() { return }
      try await Task.sleep(for: .milliseconds(50))
    }
    Issue.record("Timed out waiting for \(what)")
  }

  @Test
  func `hydrate keeps a round that is still waiting out of the drain`() async throws {
    let harness = try await makeHarness(maxBackoffRounds: 2, notBefore: Date().addingTimeInterval(3600), backoffRound: 1)
    await harness.queue.hydrate()
    await harness.queue.awaitDrainCompletion()
    #expect(await harness.session.sendMessageInvocations.isEmpty)
    #expect(harness.queue.backoffWakeAt != nil)
    await harness.queue.shutdown()
  }

  @Test
  func `hydrate sends a round whose wait elapsed while the app was closed`() async throws {
    let harness = try await makeHarness(maxBackoffRounds: 2, notBefore: Date().addingTimeInterval(-5), backoffRound: 1)
    await harness.queue.hydrate()
    try await waitUntil("the overdue round sends") { await harness.session.sendMessageInvocations.count == 1 }
    await harness.queue.shutdown()
  }

  @Test
  func `an expired opted-in send waits, then sends once more on a new timestamp`() async throws {
    let harness = try await makeHarness(maxBackoffRounds: 1)
    await harness.queue.hydrate()
    try await waitUntil("the first send is accepted") { await harness.session.sendMessageInvocations.count == 1 }
    try await waitUntil("its ACK owner is saved") {
      (try? await harness.store.fetchPendingSends(radioID: harness.radioID).first?.ackDeadline) != nil
    }

    let sendCountBefore = try #require(await harness.store.fetchMessage(id: harness.messageID)).sendCount
    try await harness.messageService.checkExpiredAcks()
    let waiting = try #require(await harness.store.fetchMessage(id: harness.messageID))
    #expect(waiting.status == .retrying)
    #expect(await harness.messageService.pendingAckCount == 0)
    let waitingRow = try #require(await harness.store.fetchPendingSends(radioID: harness.radioID).first)
    #expect(waitingRow.backoffRound == 1)
    #expect(waitingRow.notBefore != nil)

    try await waitUntil("the backoff round sends") { await harness.session.sendMessageInvocations.count == 2 }
    try await waitUntil("the retry is accepted") {
      await (try? harness.store.fetchMessage(id: harness.messageID))?.status == .sent
    }
    let resent = try #require(await harness.store.fetchMessage(id: harness.messageID))
    #expect(resent.timestamp != harness.pinnedTimestamp)
    // Backoff rounds are queue retries, not user resends.
    #expect(resent.sendCount == sendCountBefore)

    // The only round is used, so the next expiry fails the message as usual.
    try await waitUntil("its ACK owner is saved again") {
      (try? await harness.store.fetchPendingSends(radioID: harness.radioID).first?.ackDeadline) != nil
    }
    try await harness.messageService.checkExpiredAcks()
    #expect(try await harness.store.fetchMessage(id: harness.messageID)?.status == .failed)
    #expect(await harness.session.sendMessageInvocations.count == 2)
    await harness.queue.shutdown()
  }

  @Test(arguments: [0, 3])
  func `a new DM stores the backoff rounds it opted into`(rounds: Int) async throws {
    let harness = try await makeHarness(maxBackoffRounds: 0, notBefore: Date().addingTimeInterval(3600))
    let contact = try #require(await harness.store.fetchContacts(radioID: harness.radioID).first)
    let admission = try await harness.queue.enqueueDirectMessage(
      text: "opt in", to: contact, maxBackoffRounds: rounds
    )
    guard case let .queued(message) = admission else {
      Issue.record("The DM must be queued")
      return
    }
    try await waitUntil("the admitted row is saved") {
      (try? await harness.store.fetchPendingSends(radioID: harness.radioID))?
        .contains { $0.messageID == message.id } == true
    }
    let row = try #require(await harness.store.fetchPendingSends(radioID: harness.radioID).first { $0.messageID == message.id })
    #expect(row.maxBackoffRounds == rounds)
    #expect(row.backoffRound == 0)
    await harness.queue.shutdown()
  }

  @Test
  func `a send that did not opt in fails at expiry as before`() async throws {
    let harness = try await makeHarness(maxBackoffRounds: 0)
    await harness.queue.hydrate()
    try await waitUntil("the first send is accepted") { await harness.session.sendMessageInvocations.count == 1 }
    try await waitUntil("its ACK owner is saved") {
      (try? await harness.store.fetchPendingSends(radioID: harness.radioID).first?.ackDeadline) != nil
    }
    try await harness.messageService.checkExpiredAcks()
    #expect(try await harness.store.fetchMessage(id: harness.messageID)?.status == .failed)
    #expect(await harness.session.sendMessageInvocations.count == 1)
    await harness.queue.shutdown()
  }
}

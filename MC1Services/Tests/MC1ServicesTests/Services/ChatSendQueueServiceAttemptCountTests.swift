import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("ChatSendQueueService.attemptCount")
@MainActor
struct ChatSendQueueServiceAttemptCountTests {
  private struct QueueHarness {
    let service: ChatSendQueueService
    let store: PersistenceStore
    let messageID: UUID
    let radioID: UUID
    let originalTimestamp: UInt32
  }

  private static func setupQueueWithRow(
    attemptCount: Int?
  ) async throws -> QueueHarness {
    let device = Device(
      publicKey: Data(repeating: 1, count: 32),
      nodeName: "Test Device"
    )
    let container = try PersistenceStore.createContainer(inMemory: true)
    container.mainContext.insert(device)
    try container.mainContext.save()
    let store = PersistenceStore(modelContainer: container)
    let radioID = device.radioID

    let contact = Contact(
      radioID: radioID,
      publicKey: Data(repeating: 2, count: 32),
      name: "Test Contact",
      lastHeardTimestamp: 0
    )
    container.mainContext.insert(contact)
    try container.mainContext.save()
    let contactDTO = try #require(try await store.fetchContact(id: contact.id))

    let transport = SimulatorMockTransport()
    let session = MeshCoreSession(transport: transport)
    let messageService = MessageService(session: session, dataStore: store, contactService: nil)
    let pending = try await messageService.createPendingMessage(text: "Hello", to: contactDTO)

    // An older timestamp makes the first-attempt refresh observable despite whole-second precision.
    let pinnedTimestamp = UInt32(Date().addingTimeInterval(-3600).timeIntervalSince1970)
    try await store.updateMessageTimestamp(id: pending.id, timestamp: pinnedTimestamp)
    let message = try #require(try await store.fetchMessage(id: pending.id))

    let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contactDTO.id)
    let dto = PendingSendDTO(
      id: UUID(),
      radioID: radioID,
      messageID: envelope.messageID,
      kind: .dm,
      contactID: envelope.contactID,
      channelIndex: nil,
      isResend: false,
      messageText: "",
      messageTimestamp: 0,
      localNodeName: nil,
      sequence: 1,
      enqueuedAt: Date(),
      attemptCount: attemptCount
    )
    try await store.upsertPendingSend(dto)

    let channelService = ChannelService(session: session, dataStore: store, rxLogService: nil)
    let service = ChatSendQueueService(
      radioID: radioID,
      dataStore: store,
      messageService: messageService,
      channelService: channelService,
      reactionService: ReactionService()
    )

    return QueueHarness(
      service: service,
      store: store,
      messageID: message.id,
      radioID: radioID,
      originalTimestamp: message.timestamp
    )
  }

  @Test
  func `fresh send: row with attemptCount=0 first drain uses fresh timestamp`() async throws {
    let harness = try await Self.setupQueueWithRow(attemptCount: 0)

    await harness.service.hydrate()
    // Poll for both drain effects so the assertions can't observe a
    // half-applied drain on a slow runner.
    try await waitUntil(timeout: .seconds(10), "first drain must bump 0 → 1 and stamp a fresh timestamp") {
      guard
        let row = await (try? harness.store.fetchPendingSends(radioID: harness.radioID))?
          .first(where: { $0.messageID == harness.messageID }),
        let timestamp = await (try? harness.store.fetchMessage(id: harness.messageID))?.timestamp
      else { return false }
      return row.attemptCount == 1 && timestamp != harness.originalTimestamp
    }

    let rows = try await harness.store.fetchPendingSends(radioID: harness.radioID)
    let bumped = rows.first(where: { $0.messageID == harness.messageID })?.attemptCount
    #expect(bumped == 1, "first drain attempt must bump 0 → 1")

    let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
    #expect(postDrainMessage?.timestamp != harness.originalTimestamp,
            "preserveTimestamp=false: updateMessageTimestamp must stamp a fresh wire timestamp")

    await harness.service.shutdown()
  }

  @Test
  func `process restart: row with attemptCount=1 rehydrates with preserveTimestamp=true`() async throws {
    let harness = try await Self.setupQueueWithRow(attemptCount: 1)

    await harness.service.hydrate()
    try await waitUntil(timeout: .seconds(10), "rehydrate drain must bump 1 → 2") {
      let row = await (try? harness.store.fetchPendingSends(radioID: harness.radioID))?
        .first(where: { $0.messageID == harness.messageID })
      return row?.attemptCount == 2
    }

    let postDrain = try await harness.store.fetchPendingSends(radioID: harness.radioID)
    #expect(postDrain.first(where: { $0.messageID == harness.messageID })?.attemptCount == 2,
            "rehydrate drain bumps 1 → 2")

    let postDrainMessage = try await harness.store.fetchMessage(id: harness.messageID)
    #expect(postDrainMessage?.timestamp == harness.originalTimestamp,
            "rehydrate must preserve original wire timestamp so mesh dedup catches duplicate landing")

    await harness.service.shutdown()
  }
}

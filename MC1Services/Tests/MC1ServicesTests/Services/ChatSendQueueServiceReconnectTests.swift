import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ChatSendQueueService reconnect delivery")
@MainActor
struct ChatSendQueueServiceReconnectTests {
  private static let senderPublicKey = Data(repeating: 0x22, count: 32)
  private static let interruptedAckTimeoutMilliseconds: UInt32 = 30000

  @Test
  func `automatic replay after session stop persists delivery`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    try await store.saveDevice(.testDevice(radioID: radioID, publicKey: Self.senderPublicKey))
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: Data(repeating: 0x33, count: 32),
      outPathLength: 0
    )
    try await store.saveContact(contact)
    let original = try await makeConnection(store: store, radioID: radioID)
    defer { Task { await original.shutdown() } }

    let message = try await original.messageService.createPendingMessage(text: "Reconnect delivery", to: contact)
    _ = try await store.insertPendingSendAssigningSequence(PendingSendDTO(
      envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id),
      radioID: radioID
    ))
    await original.queue.hydrate()

    try await waitUntil("queued DM should reach the radio") {
      await original.sendMessagePackets.count == 1
    }
    let stored = try #require(await store.fetchMessage(id: message.id))
    await original.transport.simulateReceive(messageSentPacket(
      message: stored,
      attempt: 0,
      timeoutMilliseconds: Self.interruptedAckTimeoutMilliseconds
    ))
    try await waitForAckWait(original, messageID: message.id)
    let beforeDisconnect = try #require(await store.fetchMessage(id: message.id))

    await original.session.stop()
    await original.queue.shutdown()
    await original.queue.awaitDrainCompletion()

    let interrupted = try #require(await store.fetchMessage(id: message.id))
    #expect(interrupted.status != .failed)
    #expect(interrupted.timestamp == beforeDisconnect.timestamp)
    #expect(await original.messageService.pendingAckCount == 0)
    #expect(try await store.hasPendingSend(messageID: message.id))

    // Wait for the next wire second so a restamp changes `timestamp`.
    try await waitUntil("replay should occur in a later wire second") {
      UInt32(Date().timeIntervalSince1970) > beforeDisconnect.timestamp
    }
    let replay = try await makeConnection(store: store, radioID: radioID)
    defer { Task { await replay.shutdown() } }
    await replay.queue.hydrate()
    try await waitUntil("rebuilt queue should send the persisted DM") {
      await replay.sendMessagePackets.count == 1
    }

    let replayedMessage = try #require(await store.fetchMessage(id: message.id))
    #expect(replayedMessage.timestamp == beforeDisconnect.timestamp)
    let replayPacket = try #require(await replay.sendMessagePackets.first)
    #expect(replayPacket == PacketBuilder.sendMessage(
      to: contact.publicKey.prefix(6),
      text: message.text,
      timestamp: Date(timeIntervalSince1970: TimeInterval(beforeDisconnect.timestamp)),
      attempt: 0
    ))
    await replay.transport.simulateReceive(messageSentPacket(
      message: replayedMessage,
      attempt: 0,
      timeoutMilliseconds: Self.interruptedAckTimeoutMilliseconds
    ))
    try await waitForAckWait(replay, messageID: message.id)
    var ackPacket = Data([ResponseCode.ack.rawValue])
    ackPacket.append(expectedAck(message: replayedMessage, attempt: 0))
    await replay.transport.simulateReceive(ackPacket)

    // finalizeSend reads the contact path after the ACK, so the drain stays in flight until that read returns.
    try await waitUntil("delivery should query the current contact path") {
      await replay.transport.sentData.contains { $0.first == CommandCode.getContactByKey.rawValue }
    }
    await replay.session.dispatchForTesting(.error(code: nil))
    await replay.queue.awaitDrainCompletion()

    let delivered = try #require(await store.fetchMessage(id: message.id))
    #expect(delivered.status == .delivered)
    #expect(delivered.timestamp == beforeDisconnect.timestamp)
    #expect(try await store.hasPendingSend(messageID: message.id) == false)
  }

  private func makeConnection(store: PersistenceStore, radioID: UUID) async throws -> ConnectionHarness {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport, configuration: SessionConfiguration(defaultTimeout: 5))
    let start = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.contains { $0.first == CommandCode.appStart.rawValue }
    }
    await session.dispatchForTesting(.selfInfo(.testSelfInfo(publicKey: Self.senderPublicKey)))
    try await start.value

    let messageService = MessageService(
      session: session,
      dataStore: store,
      contactService: nil,
      config: MessageServiceConfig(maxAttempts: 3, floodAfter: 3, triggerPathDiscoveryAfterFlood: false)
    )
    let queue = ChatSendQueueService(
      radioID: radioID,
      dataStore: store,
      messageService: messageService,
      channelService: ChannelService(session: session, dataStore: store, rxLogService: nil),
      reactionService: ReactionService()
    )
    return ConnectionHarness(transport: transport, session: session, messageService: messageService, queue: queue)
  }

  private func waitForAckWait(_ connection: ConnectionHarness, messageID: UUID) async throws {
    let expectedTimeout = Double(Self.interruptedAckTimeoutMilliseconds) / 1000 * 1.2
    try await waitUntil("radio acceptance should advance the retry loop into its ACK wait") {
      let tracking = await connection.messageService.pendingAckForTest(messageID)
      let subscriptions = await connection.session.subscriberCountForTest
      return tracking?.timeout == expectedTimeout && subscriptions == 1
    }
  }

  private func expectedAck(message: MessageDTO, attempt: UInt8) -> Data {
    AckCodeBuilder.expectedAck(
      timestamp: message.timestamp,
      attempt: attempt,
      text: message.text,
      senderPublicKey: Self.senderPublicKey
    )
  }

  private func messageSentPacket(message: MessageDTO, attempt: UInt8, timeoutMilliseconds: UInt32) -> Data {
    var packet = Data([ResponseCode.messageSent.rawValue, 0])
    packet.append(expectedAck(message: message, attempt: attempt))
    withUnsafeBytes(of: timeoutMilliseconds.littleEndian) { packet.append(contentsOf: $0) }
    return packet
  }

  private struct ConnectionHarness {
    let transport: MockTransport
    let session: MeshCoreSession
    let messageService: MessageService
    let queue: ChatSendQueueService

    var sendMessagePackets: [Data] {
      get async {
        await transport.sentData.filter { $0.first == CommandCode.sendMessage.rawValue }
      }
    }

    func shutdown() async {
      await session.stop()
      await queue.shutdown()
      await queue.awaitDrainCompletion()
    }
  }
}

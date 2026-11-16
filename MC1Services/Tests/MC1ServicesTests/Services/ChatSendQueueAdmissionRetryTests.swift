import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Initial queued send preparation retry", .timeLimit(.minutes(1)))
@MainActor
struct ChatSendQueueAdmissionRetryTests {
  private static let acknowledgementTimeoutMilliseconds: UInt32 = 30000
  private static let originalTimestamp: UInt32 = 1_700_000_000

  @Test
  func `a failed recovery save parks its DM without preventing later hydration`() async throws {
    let fixture = try await Fixture.make()
    let interrupted = try #require(fixture.directMessages.first)
    await fixture.store.setPendingSendAttemptSaveFaultInjection { messageID in
      if messageID == interrupted.id { throw CocoaError(.fileWriteUnknown) }
    }

    do {
      await fixture.queue.hydrate()
      try await waitUntil("the failed DM should park while the later channel send completes") {
        guard await fixture.queue.parkedDrainCountForTesting > 0 else { return false }
        guard try await fixture.store.fetchMessage(id: fixture.channelMessage.id)?.status == .sent else { return false }
        return try await !fixture.store.hasPendingSend(messageID: fixture.channelMessage.id)
      }

      let retained = try #require(try await fixture.store.fetchMessage(id: interrupted.id))
      #expect(retained.status == .failed)
      #expect(retained.timestamp == Self.originalTimestamp)
      let pending = try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID)
      #expect(pending.map(\.messageID) == fixture.directMessages.map(\.id))
      #expect(pending.allSatisfy { $0.attemptCount == 0 && $0.ackDeadline == nil })
      #expect(await fixture.sendFrames(for: .sendMessage).isEmpty)
      #expect(await fixture.sendFrames(for: .sendChannelMessage).count == 1)

      await fixture.store.setPendingSendAttemptSaveFaultInjection(nil)
      fixture.queue.transportDidOpen()
      for (index, message) in fixture.directMessages.enumerated() {
        try await fixture.acknowledge(message, submissionCount: index + 1)
      }
      await fixture.queue.awaitDrainCompletion()

      #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).isEmpty)
      #expect(await fixture.sendFrames(for: .sendMessage).count == fixture.directMessages.count)
      #expect(await fixture.sendFrames(for: .sendChannelMessage).count == 1)
      #expect(!fixture.queue.isRetiredForTesting)
    } catch {
      await fixture.close()
      throw error
    }
    await fixture.close()
  }

  @MainActor
  private struct Fixture {
    let store: PersistenceStore
    let contact: ContactDTO
    let directMessages: [MessageDTO]
    let channelMessage: MessageDTO
    let radio: ConnectionTestRadioTransport
    let session: MeshCoreSession
    let messageService: MessageService
    let queue: ChatSendQueueService

    static func make() async throws -> Fixture {
      let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
      let contact = ContactDTO.testContact()
      let channel = ChannelDTO.testChannel(radioID: contact.radioID)
      try await store.saveContact(contact)
      try await store.saveChannel(channel)
      let interrupted = MessageDTO.testDirectMessage(
        radioID: contact.radioID, contactID: contact.id, text: "Interrupted admission",
        timestamp: ChatSendQueueAdmissionRetryTests.originalTimestamp, status: .failed
      )
      let following = MessageDTO.testDirectMessage(
        radioID: contact.radioID, contactID: contact.id, text: "Following DM"
      )
      let channelMessage = MessageDTO.testChannelMessage(
        radioID: channel.radioID, channelIndex: channel.index, text: "Following channel send", status: .pending
      )
      for message in [interrupted, following] {
        try await store.saveMessage(message)
        _ = try await store.insertPendingSendAssigningSequence(PendingSendDTO(
          envelope: DirectMessageEnvelope(messageID: message.id, contactID: contact.id), radioID: contact.radioID
        ))
      }
      try await store.saveMessage(channelMessage)
      _ = try await store.insertPendingSendAssigningSequence(PendingSendDTO(
        envelope: ChannelMessageEnvelope(
          messageID: channelMessage.id, channelIndex: channel.index, isResend: false,
          messageText: channelMessage.text, messageTimestamp: channelMessage.timestamp, localNodeName: nil
        ),
        radioID: channel.radioID
      ))

      let radio = ConnectionTestRadioTransport()
      await radio.setContact(contact.toContactFrame().toMeshContact())
      await radio.setChannel(index: channel.index, name: channel.name, secret: channel.secret)
      await radio.setResponsePackets(for: .sendMessage, packets: [])
      let session = MeshCoreSession(transport: radio)
      try await session.start()
      let messageService = MessageService(
        session: session, dataStore: store, contactService: nil,
        config: MessageServiceConfig(triggerPathDiscoveryAfterFlood: false), dmSendBudget: { .singleAttempt }
      )
      await messageService.startEventMonitoring()
      let queue = ChatSendQueueService(
        radioID: contact.radioID, dataStore: store, messageService: messageService,
        channelService: ChannelService(session: session, dataStore: store, rxLogService: nil),
        reactionService: ReactionService()
      )
      return Fixture(
        store: store, contact: contact, directMessages: [interrupted, following], channelMessage: channelMessage,
        radio: radio, session: session, messageService: messageService, queue: queue
      )
    }

    func acknowledge(_ message: MessageDTO, submissionCount: Int) async throws {
      try await waitUntil("the same queue should submit the next retained DM") {
        await sendFrames(for: .sendMessage).count == submissionCount
      }
      let submitted = try #require(try await store.fetchMessage(id: message.id))
      let pending = try #require(try await store.fetchPendingSendsForMessage(messageID: message.id).first)
      #expect(pending.attemptCount == 1)
      #expect(submitted.status == .pending)
      let frames = await sendFrames(for: .sendMessage)
      #expect(frames.last == PacketBuilder.sendMessage(
        to: contact.publicKey.prefix(6), text: submitted.text,
        timestamp: Date(timeIntervalSince1970: TimeInterval(submitted.timestamp)), attempt: 0
      ))

      let acknowledgement = AckCodeBuilder.expectedAck(
        timestamp: submitted.timestamp, attempt: 0, text: submitted.text, senderPublicKey: radio.publicKey
      )
      var accepted = Data([ResponseCode.messageSent.rawValue, 0]) + acknowledgement
      withUnsafeBytes(of: ChatSendQueueAdmissionRetryTests.acknowledgementTimeoutMilliseconds.littleEndian) {
        accepted.append(contentsOf: $0)
      }
      await radio.inject(accepted)
      await radio.inject(Data([ResponseCode.ack.rawValue]) + acknowledgement)
      try await waitUntil("the recovered DM should record delivery") {
        try await store.fetchMessage(id: message.id)?.status == .delivered
      }
    }

    func sendFrames(for command: CommandCode) async -> [Data] {
      await radio.sentData.filter { $0.first == command.rawValue }
    }

    func close() async {
      await session.stop()
      await queue.shutdown()
      await messageService.stopEventMonitoring()
    }
  }
}

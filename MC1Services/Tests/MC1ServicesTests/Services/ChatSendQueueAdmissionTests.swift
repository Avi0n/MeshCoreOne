import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Initial queued send admission", .timeLimit(.minutes(1)))
@MainActor
struct ChatSendQueueAdmissionTests {
  private nonisolated static let text = "Initial queued message"
  private nonisolated static let localNodeName = "Local Radio"

  @Test(arguments: [false, true])
  func `admission publishes the persisted Message before queue execution`(isChannel: Bool) async throws {
    let fixture = try await Fixture.make()
    let observation = Observation()
    await fixture.store.setPendingSendInsertHandler {
      let published = try #require(await observation.message)
      #expect(try await fixture.store.fetchMessage(id: published.id)?.text == Self.text)
      #expect(try await fixture.store.fetchPendingSends(radioID: fixture.radioID).isEmpty)
      #expect(await fixture.transport.sentData.isEmpty)
    }
    do {
      let result = try await fixture.submit(isChannel: isChannel) { observation.message = $0 }
      await fixture.queue.shutdown()
      guard case let .queued(message) = result else {
        Issue.record("Expected durable queue admission")
        return
      }
      #expect(observation.message?.id == message.id)
      #expect(try await fixture.messages(isChannel: isChannel).map(\.id) == [message.id])
      if !isChannel {
        #expect(try await fixture.store.fetchContact(id: fixture.contact.id)?.lastMessageDate == message.date)
      }
    } catch {
      await fixture.queue.shutdown()
      throw error
    }
  }

  @Test(arguments: [false, true])
  func `foreign Radio IDs reject before creating a Message`(isChannel: Bool) async throws {
    let fixture = try await Fixture.make(recipientRadioID: UUID())
    do {
      _ = try await fixture.submit(isChannel: isChannel)
      Issue.record("Expected admission to reject the unavailable queue")
    } catch ChatSendQueueServiceError.notConnected {
      #expect(try await fixture.messages(isChannel: isChannel).isEmpty)
      #expect(try await fixture.store.fetchPendingSends(radioID: fixture.radioID).isEmpty)
      #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).isEmpty)
    } catch {
      await fixture.queue.shutdown()
      throw error
    }
    await fixture.queue.shutdown()
  }

  @Test(arguments: [false, true])
  func `retirement waits for admission repair and preserves inserted work for hydration`(isChannel: Bool) async throws {
    let fixture = try await Fixture.make()
    let gate = Gate()
    let observation = Observation()
    await fixture.store.setPendingSendInsertHandler { await gate.wait() }
    let admission = Task { try await fixture.submit(isChannel: isChannel) }
    do {
      try await waitUntil("admission should reach pending-send insertion") { gate.arrived }
      let retirement = Task {
        await fixture.queue.shutdown()
        observation.retirementFinished = true
      }
      try await waitUntil("retirement should revoke new admission") { fixture.queue.isRetiredForTesting }
      #expect(!observation.retirementFinished)
      gate.open()
      let result = try await admission.value
      await retirement.value
      guard case let .enqueueFailed(message, error) = result else {
        Issue.record("Text admission must report retirement after saving its Message")
        return
      }
      #expect(error is CancellationError)
      #expect(try await fixture.store.fetchMessage(id: message.id)?.status == .failed)
      let pending = try #require(try await fixture.store.fetchPendingSends(radioID: fixture.radioID).first)
      #expect(pending.messageID == message.id)
      #expect(pending.attemptCount == 0)
      #expect(await fixture.transport.sentData.isEmpty)

    } catch {
      gate.open()
      _ = await admission.result
      await fixture.queue.shutdown()
      throw error
    }
  }

  @MainActor
  private struct Fixture {
    let radioID: UUID
    let store: PersistenceStore
    let contact: ContactDTO
    let channel: ChannelDTO
    let transport: MockTransport
    let queue: ChatSendQueueService

    static func make(recipientRadioID: UUID? = nil) async throws -> Fixture {
      let radioID = UUID()
      let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
      let contact = ContactDTO.testContact(radioID: recipientRadioID ?? radioID)
      let channel = ChannelDTO.testChannel(radioID: recipientRadioID ?? radioID)
      try await store.saveContact(contact)
      try await store.saveChannel(channel)
      let transport = MockTransport()
      let session = MeshCoreSession(transport: transport)
      let queue = ChatSendQueueService(
        radioID: radioID, dataStore: store,
        messageService: MessageService(session: session, dataStore: store, contactService: nil),
        channelService: ChannelService(session: session, dataStore: store, rxLogService: nil),
        reactionService: ReactionService()
      )
      return Fixture(
        radioID: radioID, store: store, contact: contact, channel: channel,
        transport: transport, queue: queue
      )
    }

    func submit(
      isChannel: Bool, onMessageCreated: (@MainActor (MessageDTO) -> Void)? = nil
    ) async throws -> ChatSendAdmission {
      if isChannel {
        return try await queue.enqueueChannelMessage(
          text: ChatSendQueueAdmissionTests.text, to: channel,
          localNodeName: ChatSendQueueAdmissionTests.localNodeName, onMessageCreated: onMessageCreated
        )
      }
      return try await queue.enqueueDirectMessage(
        text: ChatSendQueueAdmissionTests.text, to: contact, onMessageCreated: onMessageCreated
      )
    }

    func messages(isChannel: Bool) async throws -> [MessageDTO] {
      if isChannel {
        return try await store.fetchMessages(radioID: channel.radioID, channelIndex: channel.index)
      }
      return try await store.fetchMessages(contactID: contact.id)
    }
  }

  @MainActor
  private final class Observation {
    var message: MessageDTO?
    var retirementFinished = false
  }

  @MainActor
  private final class Gate {
    var arrived = false
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      arrived = true
      guard !opened else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      opened = true
      continuation?.resume()
      continuation = nil
    }
  }
}

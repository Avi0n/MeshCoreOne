import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessageService ACK finalization")
@MainActor
struct MessageServiceACKFinalizationTests {
  private let senderKey = Data(repeating: 0xAB, count: 32)

  @Test
  func `an owner installed during ownerless delivery is deleted before settlement finishes`() async throws {
    let fixture = try await makeFixture()
    let command = FirstWriteGate()
    let delivery = FirstWriteGate()
    defer { command.release(); delivery.release() }
    await fixture.session.setSendMessageObserver { await command.park() }
    await fixture.store.setUpdateMessageAckObserver { status in
      if status == .delivered { await delivery.park() }
    }
    let events = fixture.service.statusEvents()
    let send = Task {
      try await fixture.service.sendDirectMessage(text: "early ACK", to: fixture.contact)
    }
    try await waitUntil("the inline command is held") { command.isParked }
    let invocation = try #require(await fixture.session.sendMessageInvocations.first)
    let code = AckCodeBuilder.expectedAck(
      timestamp: UInt32(invocation.timestamp.timeIntervalSince1970), attempt: invocation.attempt,
      text: invocation.text, senderPublicKey: senderKey
    )
    let acknowledgement = Task {
      await fixture.service.handleAcknowledgement(code: code, tripTime: 77)
    }
    try await waitUntil("ownerless terminal persistence is held") { delivery.isParked }

    command.release()
    let accepted = try await send.value
    #expect(accepted.status == .sent)
    let owner = try #require(await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).first)
    #expect(owner.messageID == accepted.id)
    #expect(owner.ackDeadline != nil)
    let nextSend = Task { try await fixture.gate.acquire() }
    defer { nextSend.cancel() }
    try await waitUntil("admission waits for terminal persistence") {
      await fixture.gate.waiterCountForTesting == 1
    }

    delivery.release()
    await acknowledgement.value
    let admitted = try await nextSend.value
    await fixture.gate.stopIssuing(admitted)
    let delivered = try #require(await fixture.store.fetchMessage(id: accepted.id))
    #expect(delivered.status == .delivered)
    #expect(delivered.roundTripTime == 77)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).isEmpty)
    #expect(await fixture.service.pendingAckCount == 0)
    #expect(await fixture.session.sendMessageInvocations.count == 1)
    let recorded = await fixture.service.drainStatusEvents(events)
    #expect(recorded.failedIDs.isEmpty)
    let deliveries = recorded.compactMap { event -> UUID? in
      guard case let .statusResolved(messageID, .delivered, roundTripTime) = event else { return nil }
      #expect(roundTripTime == 77)
      return messageID
    }
    #expect(deliveries == [accepted.id])
  }

  @Test
  func `waiter-first delivery retains ownership through a failed save and retry`() async throws {
    let fixture = try await makeFixture()
    await fixture.session.setWaitForEventResult(.acknowledgement(code: fixture.code, tripTime: nil))
    let terminal = FirstWriteGate()
    defer { terminal.release() }
    await fixture.store.setResolvePendingAcknowledgementObserver { await terminal.park() }
    await fixture.store.setStubbedResolvePendingAcknowledgementError(InjectedError.save)
    let events = fixture.service.statusEvents()
    var sendFinished = false
    let send = Task {
      defer { sendFinished = true }
      return try await fixture.service.sendMessageWithRetry(text: "waiter ACK", to: fixture.contact)
    }
    try await waitUntil("the waiter reaches terminal persistence") { terminal.isParked || sendFinished }
    try #require(terminal.isParked, "The waiter must use the common durable settlement path")
    let message = try #require(
      await fixture.store.fetchMessages(contactID: fixture.contact.id, limit: 1, offset: 0).first
    )
    let owner = try #require(await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).first)
    let nextSend = Task { try await fixture.gate.acquire() }
    defer { nextSend.cancel() }
    try await waitUntil("a competing send waits for the waiter settlement") {
      await fixture.gate.waiterCountForTesting == 1
    }
    await fixture.service.handleAcknowledgement(code: fixture.code, tripTime: 999)
    try await fixture.service.checkExpiredAcks()
    #expect(await fixture.store.resolvePendingAcknowledgementCallCount == 1)
    #expect(await fixture.gate.waiterCountForTesting == 1)

    terminal.release()
    let result = try await send.value
    #expect(result.status == .sent)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID) == [owner])
    #expect(await fixture.service.pendingAckCount == 1)
    #expect(await fixture.gate.waiterCountForTesting == 1)
    await fixture.store.setStubbedResolvePendingAcknowledgementError(nil)
    let deadline = try #require(owner.ackDeadline)
    try await waitUntil("the original ACK deadline has elapsed") { Date() > deadline }
    try await fixture.service.checkExpiredAcks()
    let admitted = try await nextSend.value
    await fixture.gate.stopIssuing(admitted)
    let delivered = try #require(await fixture.store.fetchMessage(id: message.id))
    #expect(delivered.status == .delivered)
    #expect(delivered.roundTripTime == nil)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).isEmpty)
    #expect(await fixture.service.pendingAckCount == 0)
    #expect(await fixture.session.sendMessageInvocations.count == 1)
    #expect(await fixture.store.resolvePendingAcknowledgementCallCount == 2)
    let recorded = await fixture.service.drainStatusEvents(events)
    #expect(recorded.failedIDs.isEmpty)
    let deliveries = recorded.compactMap { event -> UUID? in
      guard case let .statusResolved(messageID, .delivered, roundTripTime) = event else { return nil }
      #expect(roundTripTime == delivered.roundTripTime)
      return messageID
    }
    #expect(deliveries == [message.id])
  }

  private struct Fixture {
    let store: MockPersistenceStore
    let session: MockMeshCoreSession
    let service: MessageService
    let gate: PlainTextSendGate
    let contact: ContactDTO
    let code: Data
  }

  private func makeFixture() async throws -> Fixture {
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let gate = PlainTextSendGate()
    let code = Data([0x11, 0x22, 0x33, 0x44])
    await session.setCurrentSelfInfo(.testSelfInfo(publicKey: senderKey))
    await session.setSendMessageResult(.success(MessageSentInfo(
      route: 0, expectedAck: code, suggestedTimeoutMs: 0
    )))
    let service = MessageService(
      session: session, dataStore: store, contactService: nil,
      config: MessageServiceConfig(minTimeout: 0, ackGiveUpWindow: 0), plainTextSendGate: gate
    )
    let contact = ContactDTO.testContact()
    try await store.saveContact(contact)
    return Fixture(store: store, session: session, service: service, gate: gate, contact: contact, code: code)
  }

  private enum InjectedError: Error {
    case save
  }

  private final class FirstWriteGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Never>?
    private var didEnter = false

    var isParked: Bool {
      lock.lock()
      defer { lock.unlock() }
      return continuation != nil
    }

    func park() async {
      await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
        lock.lock()
        if didEnter {
          lock.unlock()
          continuation.resume()
          return
        }
        didEnter = true
        self.continuation = continuation
        lock.unlock()
      }
    }

    func release() {
      lock.lock()
      let continuation = continuation
      self.continuation = nil
      lock.unlock()
      continuation?.resume()
    }
  }
}

import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessageService ACK settlement")
@MainActor
struct MessageServiceACKSettlementTests {
  @Test(arguments: [false, true])
  func `competing ACK and expiry callers leave terminal persistence to one owner`(afterCommit: Bool) async throws {
    let fixture = try await makeFixture()
    let events = fixture.service.statusEvents()
    let heldWrite = FirstWriteGate()
    defer { heldWrite.release() }
    if afterCommit {
      await fixture.store.setResolvePendingAcknowledgementCompletionObserver { await heldWrite.park() }
    } else {
      await fixture.store.setResolvePendingAcknowledgementObserver { await heldWrite.park() }
    }
    let nextSend = Task { try await fixture.gate.acquire() }
    defer { nextSend.cancel() }
    try await waitUntil("the next send waits for ACK settlement") {
      await fixture.gate.waiterCountForTesting == 1
    }
    let resolution = Task {
      await fixture.service.handleAcknowledgement(code: fixture.code, tripTime: 40)
    }
    try await waitUntil("the first terminal write is held") { heldWrite.isParked }

    await fixture.service.handleAcknowledgement(code: fixture.code, tripTime: 999)
    try await fixture.service.failAllPendingMessages()
    try await fixture.service.checkExpiredAcks()

    #expect(await fixture.store.resolvePendingAcknowledgementCallCount == 1)
    #expect(await fixture.gate.waiterCountForTesting == 1)
    #expect(await fixture.service.pendingAckCount == 1)
    #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == (afterCommit ? .delivered : .sent))
    let heldOwners = try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID)
    #expect(heldOwners == (afterCommit ? [] : [fixture.owner]))

    heldWrite.release()
    await resolution.value
    let admitted = try await nextSend.value
    await fixture.gate.stopIssuing(admitted)
    let delivered = try #require(await fixture.store.fetchMessage(id: fixture.message.id))
    #expect(delivered.status == .delivered)
    #expect(delivered.roundTripTime == 40)
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).isEmpty)
    #expect(await fixture.service.pendingAckCount == 0)
    #expect(await fixture.session.sendMessageInvocations.count == 1)
    let recorded = await fixture.service.drainStatusEvents(events)
    #expect(recorded.failedIDs.isEmpty)
    #expect(recorded.resolvedIDs == [fixture.message.id])
    guard case let .statusResolved(_, status, roundTripTime) = try #require(recorded.first) else {
      Issue.record("Settlement must publish the committed delivery")
      return
    }
    #expect(status == .delivered)
    #expect(roundTripTime == delivered.roundTripTime)
  }

  @Test(arguments: [false, true])
  func `ACK during an expiry write only changes the outcome when that write fails`(saveFails: Bool) async throws {
    let fixture = try await makeFixture()
    let events = fixture.service.statusEvents()
    let heldWrite = FirstWriteGate()
    defer { heldWrite.release() }
    await fixture.store.setResolvePendingAcknowledgementObserver { await heldWrite.park() }
    if saveFails {
      await fixture.store.setStubbedResolvePendingAcknowledgementError(InjectedError.save)
    }
    let nextSend = Task { try await fixture.gate.acquire() }
    defer { nextSend.cancel() }
    try await waitUntil("the next send waits for ACK settlement") {
      await fixture.gate.waiterCountForTesting == 1
    }
    let expiry = Task { try await fixture.service.checkExpiredAcks() }
    try await waitUntil("the expiry write is held") { heldWrite.isParked }

    await fixture.service.handleAcknowledgement(code: fixture.code, tripTime: 77)
    #expect(await fixture.store.resolvePendingAcknowledgementCallCount == 1)
    #expect(await fixture.gate.waiterCountForTesting == 1)

    heldWrite.release()
    try await expiry.value
    if saveFails {
      #expect(try await fixture.store.fetchMessage(id: fixture.message.id)?.status == .sent)
      #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID) == [fixture.owner])
      #expect(await fixture.service.pendingAckCount == 1)
      #expect(await fixture.gate.waiterCountForTesting == 1)

      await fixture.store.setStubbedResolvePendingAcknowledgementError(nil)
      try await fixture.service.checkExpiredAcks()
    }
    let admitted = try await nextSend.value
    await fixture.gate.stopIssuing(admitted)
    let settled = try #require(await fixture.store.fetchMessage(id: fixture.message.id))
    #expect(settled.status == (saveFails ? .delivered : .failed))
    #expect(settled.ackCode == fixture.code.ackCodeUInt32)
    #expect(settled.roundTripTime == (saveFails ? 77 : nil))
    #expect(try await fixture.store.fetchPendingSends(radioID: fixture.contact.radioID).isEmpty)
    #expect(await fixture.service.pendingAckCount == 0)
    #expect(await fixture.store.resolvePendingAcknowledgementCallCount == (saveFails ? 2 : 1))
    #expect(await fixture.session.sendMessageInvocations.count == 1)
    let recorded = await fixture.service.drainStatusEvents(events)
    #expect(recorded.failedIDs == (saveFails ? [] : [fixture.message.id]))
    #expect(recorded.resolvedIDs == (saveFails ? [fixture.message.id] : []))
    if saveFails {
      guard case let .statusResolved(_, status, roundTripTime) = try #require(recorded.first) else {
        Issue.record("Settlement must publish the committed delivery")
        return
      }
      #expect(status == .delivered)
      #expect(roundTripTime == settled.roundTripTime)
    }
  }

  private struct Fixture {
    let store: MockPersistenceStore
    let session: MockMeshCoreSession
    let service: MessageService
    let gate: PlainTextSendGate
    let contact: ContactDTO
    let message: MessageDTO
    let owner: PendingSendDTO
    let code: Data
  }

  private func makeFixture() async throws -> Fixture {
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let gate = PlainTextSendGate()
    let code = Data([0x11, 0x22, 0x33, 0x44])
    await session.setCurrentSelfInfo(.testSelfInfo())
    await session.setSendMessageResult(.success(MessageSentInfo(
      route: 0, expectedAck: code, suggestedTimeoutMs: 0
    )))
    let service = MessageService(
      session: session, dataStore: store, contactService: nil,
      config: MessageServiceConfig(ackGiveUpWindow: 0), plainTextSendGate: gate
    )
    let contact = ContactDTO.testContact()
    try await store.saveContact(contact)
    let message = try await service.sendDirectMessage(text: "ACK settlement", to: contact)
    let owner = try #require(await store.fetchPendingSends(radioID: contact.radioID).first)
    let deadline = try #require(owner.ackDeadline)
    try await waitUntil("the original ACK deadline has elapsed") { Date() > deadline }
    return Fixture(
      store: store, session: session, service: service, gate: gate,
      contact: contact, message: message, owner: owner, code: code
    )
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

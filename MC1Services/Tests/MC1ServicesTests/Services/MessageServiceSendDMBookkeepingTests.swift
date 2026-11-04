import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessageService DM post-send bookkeeping")
struct MessageServiceSendDMBookkeepingTests {
  @Test
  @MainActor
  func `deleting a DM during send removes orphan ACK tracking without reporting failure`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10)
    )
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
    defer { Task { await session.stop() } }

    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let service = MessageService(session: session, dataStore: dataStore, contactService: nil)

    let statusEvents = service.statusEvents()

    let contact = ContactDTO.testContact(radioID: UUID())

    let sendTask = Task {
      try await service.sendDirectMessage(text: "hi", to: contact)
    }

    try await waitUntil("send should issue CMD_SEND_TXT_MSG") {
      await transport.sentData.count == 2
    }

    // Delete before the acceptance response; no Message remains for
    // delivery tracking.
    let saved = try await dataStore.fetchMessages(contactID: contact.id, limit: 10, offset: 0)
    let messageID = try #require(saved.first?.id)
    try await dataStore.deleteMessage(id: messageID)

    var msgSent = Data([ResponseCode.messageSent.rawValue])
    msgSent.append(0)
    msgSent.append(Data([0xAB, 0xCD, 0xEF, 0x12]))
    msgSent.append(uint32Bytes(5000))
    await transport.simulateReceive(msgSent)

    await #expect(throws: Error.self) {
      _ = try await sendTask.value
    }

    #expect(await service.pendingAckCount == 0)
    #expect(try await dataStore.fetchPendingSends(radioID: contact.radioID).isEmpty)
    #expect(try await dataStore.fetchMessage(id: messageID) == nil)
    #expect(await service.drainStatusEvents(statusEvents).failedIDs.isEmpty)
  }

  @Test
  func `a genuine post-send write failure retains both ACK owners and accepts the valid ACK`() async throws {
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    await session.setCurrentSelfInfo(.testSelfInfo())
    let code = Data([0xAB, 0xCD, 0xEF, 0x12])
    await session.setSendMessageResult(.success(MessageSentInfo(
      route: 0, expectedAck: code, suggestedTimeoutMs: 5000
    )))
    await session.setSendMessageObserver {
      await store.setStubbedUpdateMessageStatusError(InjectedBookkeepingError.simulated)
    }
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    let events = service.statusEvents()
    let contact = ContactDTO.testContact()
    try await store.saveContact(contact)

    let accepted = try await service.sendDirectMessage(text: "hi", to: contact)

    #expect(accepted.status == .sent)
    #expect(await session.sendMessageInvocations.count == 1)
    #expect(await store.updatedMessageAcks.count == 1, "The post-send write must have been attempted")
    #expect(await service.pendingAckCount == 1)
    let owner = try #require(await store.fetchPendingSends(radioID: contact.radioID).first)
    #expect(owner.messageID == accepted.id)
    #expect(owner.ackCodes?.contains(code) == true)
    #expect(owner.ackDeadline != nil)
    #expect(owner.attemptCount == 0)

    await store.setStubbedUpdateMessageStatusError(nil)
    await service.handleAcknowledgement(code: code, tripTime: 123)

    #expect(try await store.fetchMessage(id: accepted.id)?.status == .delivered)
    #expect(try await store.fetchMessage(id: accepted.id)?.roundTripTime == 123)
    #expect(await service.pendingAckCount == 0)
    #expect(try await store.fetchPendingSends(radioID: contact.radioID).isEmpty)
    let recorded = await service.drainStatusEvents(events)
    #expect(recorded.failedIDs.isEmpty)
    #expect(recorded.resolvedIDs == [accepted.id])
  }

  @Test
  @MainActor
  func `a thrown delivery write before the owner id is installed keeps the owner and the matched code`() async throws {
    let code = Data([0xAB, 0xCD, 0xEF, 0x12])
    let harness = try await makeHarness(ackGiveUpWindow: 0, suggestedTimeoutMs: 0, code: code)
    let gate = SuspendGate()
    await harness.service.setAcknowledgementInstallGateForTesting {
      await gate.park()
    }
    await harness.store.setStubbedUpdateMessageStatusError(InjectedBookkeepingError.simulated)
    let send = Task {
      try await harness.service.sendDirectMessage(text: "held delivery", to: harness.contact)
    }
    try await waitUntil("owner row is committed before the id is installed") {
      guard gate.isParked else { return false }
      return try await harness.store.fetchPendingSends(radioID: harness.contact.radioID).count == 1
    }
    let messageID = try #require(
      try await harness.store.fetchMessages(contactID: harness.contact.id, limit: 1, offset: 0).first?.id
    )
    #expect(try await harness.store.fetchMessage(id: messageID)?.status == .sent)
    #expect(await harness.service.pendingAckForTest(messageID)?.pendingSendID == nil)
    #expect(await harness.service.pendingAckForTest(messageID)?.isDelivered == false)

    await harness.service.handleAcknowledgement(code: code, tripTime: 77)
    try await waitUntil("give-up deadline has elapsed") {
      guard let tracking = await harness.service.pendingAckForTest(messageID) else { return false }
      let deadline = tracking.deadline ?? tracking.sentAt.addingTimeInterval(max(0, tracking.timeout))
      return Date() > deadline
    }
    try await harness.service.checkExpiredAcks()

    #expect(try await harness.store.fetchMessage(id: messageID)?.status == .sent)
    #expect(await harness.service.pendingAckForTest(messageID)?.isDelivered == false)
    #expect(await harness.service.pendingAckForTest(messageID)?.matchedAckCode == code.ackCodeUInt32)
    #expect(await harness.service.pendingAckForTest(messageID)?.pendingSendID == nil)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.contact.radioID).count == 1)
    #expect(await harness.store.updatedMessageAcks.map(\.status) == [.delivered, .delivered])
    #expect(await harness.store.updatedMessageStatuses.isEmpty)

    await harness.store.setStubbedUpdateMessageStatusError(nil)
    gate.release()
    let accepted = try await send.value
    #expect(accepted.status == .delivered)
    #expect(accepted.roundTripTime == 77)
    #expect(await harness.service.pendingAckCount == 0)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.contact.radioID).isEmpty)
  }

  @Test
  func `an installed owner resolves one acknowledgement into delivery`() async throws {
    let code = Data([0x11, 0x22, 0x33, 0x44])
    let harness = try await makeHarness(code: code)
    let accepted = try await harness.service.sendDirectMessage(text: "installed", to: harness.contact)
    #expect(await harness.service.pendingAckForTest(accepted.id)?.pendingSendID != nil)
    let events = harness.service.statusEvents()
    await harness.service.handleAcknowledgement(code: code, tripTime: 15)
    #expect(try await harness.store.fetchMessage(id: accepted.id)?.status == .delivered)
    #expect(try await harness.store.fetchMessage(id: accepted.id)?.roundTripTime == 15)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.contact.radioID).isEmpty)
    #expect(await harness.service.pendingAckCount == 0)
    let recorded = await harness.service.drainStatusEvents(events)
    #expect(recorded.failedIDs.isEmpty)
    #expect(recorded.resolvedIDs == [accepted.id])
  }

  @Test
  @MainActor
  func `expiry without a matched acknowledgement fails the message and deletes the owner`() async throws {
    let harness = try await makeHarness(ackGiveUpWindow: 0, suggestedTimeoutMs: 0)
    let accepted = try await harness.service.sendDirectMessage(text: "expire", to: harness.contact)
    #expect(await harness.service.pendingAckForTest(accepted.id)?.matchedAckCode == nil)
    #expect(await harness.service.pendingAckForTest(accepted.id)?.pendingSendID != nil)
    let events = harness.service.statusEvents()
    try await waitUntil("give-up deadline has elapsed") {
      guard let tracking = await harness.service.pendingAckForTest(accepted.id) else { return false }
      let deadline = tracking.deadline ?? tracking.sentAt
      return Date() > deadline
    }
    try await harness.service.checkExpiredAcks()
    #expect(try await harness.store.fetchMessage(id: accepted.id)?.status == .failed)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.contact.radioID).isEmpty)
    #expect(await harness.service.pendingAckCount == 0)
    let recorded = await harness.service.drainStatusEvents(events)
    #expect(recorded.failedIDs == [accepted.id])
    #expect(recorded.resolvedIDs.isEmpty)
  }

  @Test
  @MainActor
  func `a thrown resolve leaves the owner and retries the matched code without another acknowledgement`() async throws {
    let code = Data([0xAB, 0xCD, 0xEF, 0x12])
    let harness = try await makeHarness(ackGiveUpWindow: 0, suggestedTimeoutMs: 0, code: code)
    let accepted = try await harness.service.sendDirectMessage(text: "resolve throws", to: harness.contact)
    #expect(await harness.service.pendingAckForTest(accepted.id)?.pendingSendID != nil)
    await harness.store.setStubbedResolvePendingAcknowledgementError(InjectedBookkeepingError.simulated)
    await harness.service.handleAcknowledgement(code: code, tripTime: 40)
    #expect(try await harness.store.fetchMessage(id: accepted.id)?.status == .sent)
    #expect(await harness.service.pendingAckForTest(accepted.id)?.isDelivered == false)
    #expect(await harness.service.pendingAckForTest(accepted.id)?.matchedAckCode == code.ackCodeUInt32)
    #expect(await harness.service.pendingAckForTest(accepted.id)?.pendingSendID != nil)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.contact.radioID).count == 1)
    await harness.store.setStubbedResolvePendingAcknowledgementError(nil)
    try await waitUntil("give-up deadline has elapsed") {
      guard let tracking = await harness.service.pendingAckForTest(accepted.id) else { return false }
      let deadline = tracking.deadline ?? tracking.sentAt
      return Date() > deadline
    }
    try await harness.service.checkExpiredAcks()
    #expect(try await harness.store.fetchMessage(id: accepted.id)?.status == .delivered)
    #expect(try await harness.store.fetchMessage(id: accepted.id)?.roundTripTime == 40)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.contact.radioID).isEmpty)
    #expect(await harness.service.pendingAckCount == 0)
  }

  @Test
  func `retired inline admission rejects a stale caller before saving a message`() async throws {
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    let service = MessageService(session: session, dataStore: store, contactService: nil)
    let contact = ContactDTO.testContact()
    await service.retireInlineSends()
    await #expect(throws: MessageServiceError.self) {
      _ = try await service.sendDirectMessage(text: "stale reply", to: contact)
    }
    #expect(try await store.fetchMessages(contactID: contact.id, limit: 1, offset: 0).isEmpty)
    #expect(await session.sendMessageInvocations.isEmpty)
  }

  @Test
  @MainActor
  func `a failed inline send releases a parked retirement`() async throws {
    let session = MockMeshCoreSession()
    await session.setCurrentSelfInfo(.testSelfInfo())
    await session.setSendMessageResult(.failure(CancellationError()))
    let gate = SuspendGate()
    await session.setSendMessageObserver { await gate.park() }
    let service = MessageService(
      session: session, dataStore: MockPersistenceStore(), contactService: nil
    )
    let send = Task {
      try await service.sendDirectMessage(text: "failed reply", to: .testContact())
    }
    try await waitUntil("the radio exchange is held") { gate.isParked }
    let retirement = Task { await service.retireInlineSends() }
    try await waitUntil("retirement is parked on the inline send") {
      await service.inlineSendWaiterCountForTesting > 0
    }
    gate.release()
    await #expect(throws: Error.self) { _ = try await send.value }
    await retirement.value
    #expect(await service.inlineSendsInFlight == 0)
  }

  private func makeHarness(
    ackGiveUpWindow: TimeInterval = 30,
    suggestedTimeoutMs: UInt32 = 5000,
    code: Data = Data([0xAB, 0xCD, 0xEF, 0x12])
  ) async throws -> (store: MockPersistenceStore, service: MessageService, contact: ContactDTO, code: Data) {
    let store = MockPersistenceStore()
    let session = MockMeshCoreSession()
    await session.setCurrentSelfInfo(.testSelfInfo())
    await session.setSendMessageResult(.success(MessageSentInfo(
      route: 0, expectedAck: code, suggestedTimeoutMs: suggestedTimeoutMs
    )))
    let service = MessageService(
      session: session, dataStore: store, contactService: nil,
      config: MessageServiceConfig(ackGiveUpWindow: ackGiveUpWindow)
    )
    let contact = ContactDTO.testContact()
    try await store.saveContact(contact)
    return (store, service, contact, code)
  }

  private enum InjectedBookkeepingError: Error {
    case simulated
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(22)
    payload.append(22)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(int32Bytes(0))
    payload.append(int32Bytes(0))
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(uint32Bytes(915_000))
    payload.append(uint32Bytes(125_000))
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }

  private func int32Bytes(_ value: Double) -> Data {
    withUnsafeBytes(of: Int32(value.rounded()).littleEndian) { Data($0) }
  }

  private func uint32Bytes(_ value: UInt32) -> Data {
    withUnsafeBytes(of: value.littleEndian) { Data($0) }
  }
}

private final class SuspendGate: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: CheckedContinuation<Void, Never>?
  private var parked = false

  var isParked: Bool {
    lock.lock()
    defer { lock.unlock() }
    return parked
  }

  func park() async {
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      lock.lock()
      parked = true
      self.continuation = continuation
      lock.unlock()
    }
  }

  func release() {
    lock.lock()
    let continuation = continuation
    self.continuation = nil
    parked = false
    lock.unlock()
    continuation?.resume()
  }
}

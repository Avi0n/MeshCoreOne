import Foundation
@testable import MC1Services
import os
import Testing

extension PlainTextSendGateTests {
  @Test
  func `table full after acceptance ends the pass and keeps the accepted ack window`() async throws {
    let ackWindow: TimeInterval = 2
    let script = RadioScript(senderKey: senderKey, suggestedTimeoutMs: { $0 == 4 ? 250 : 10 }, sendReply: { attempt, _ in
      attempt == 5 ? .tableFull : .accepted
    })
    let harness = try await partialSendHarness(script: script, ackGiveUpWindow: ackWindow)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0xC1)
    let message = try await harness.service.createPendingMessage(text: "partial acceptance", to: contact)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: message.id, contactID: contact.id))

    try await waitUntil("the last accepted command is waiting for its ACK") {
      await harness.service.pendingAckForTest(message.id)?.timeout == 0.3
    }
    let acceptedAt = try #require(await harness.service.pendingAckForTest(message.id)?.sentAt)
    try await waitUntil("the rejected command ends the wire pass") {
      await !harness.queue.isDMDrainingForTesting
    }
    let owner = try #require(try await harness.pendingRow(message.id))
    #expect(owner.ackDeadline == acceptedAt.addingTimeInterval(ackWindow))

    let earlierAck = try #require(script.codes().dropFirst().first)
    await harness.service.handleAcknowledgement(code: earlierAck, tripTime: 25)
    #expect(try await harness.store.fetchMessage(id: message.id)?.status == .delivered)
    #expect(try await harness.pendingRow(message.id) == nil)
    #expect(await harness.sendPackets().map { $0[2] } == [0, 1, 2, 3, 4, 5, 5, 5, 5])
  }

  @Test
  func `an earlier ack during pool backoff prevents the next submission`() async throws {
    let script = RadioScript(senderKey: senderKey, sendReply: { attempt, submission in
      attempt == 5 && submission == 1 ? .tableFull : .accepted
    })
    let harness = try await partialSendHarness(script: script, poolDelay: 0.2)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0xC2)
    let message = try await harness.service.createPendingMessage(text: "ack in backoff", to: contact)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: message.id, contactID: contact.id))
    try await waitUntil("the first attempt-5 command is on the wire") { await harness.sendCount() == 6 }

    let earlierAck = try #require(script.codes().dropFirst().first)
    await harness.service.handleAcknowledgement(code: earlierAck, tripTime: 30)
    try await waitUntil("delivery ends the wire pass") { await !harness.queue.isDMDrainingForTesting }
    #expect(try await harness.store.fetchMessage(id: message.id)?.status == .delivered)
    #expect(await harness.sendPackets().map { $0[2] } == [0, 1, 2, 3, 4, 5])
  }

  @Test
  func `a timeout keeps the predicted ack from the last pool submission`() async throws {
    let uncertainSubmission = OSAllocatedUnfairLock<Date?>(initialState: nil)
    let script = RadioScript(senderKey: senderKey, sendReply: { attempt, submission in
      guard attempt == 2 else { return .accepted }
      guard submission == 4 else { return .tableFull }
      uncertainSubmission.withLock { $0 = Date() }
      return .missing
    })
    let ackWindow: TimeInterval = 1
    let schedulingTolerance: TimeInterval = 0.1
    let harness = try await partialSendHarness(script: script, ackGiveUpWindow: ackWindow, poolDelay: 0.2)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0xC3)
    let message = try await harness.service.createPendingMessage(text: "timeout after pool backoff", to: contact)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: message.id, contactID: contact.id))

    try await waitUntil("the timed-out command ends the wire pass") { await !harness.queue.isDMDrainingForTesting }
    let submittedAt = try #require(uncertainSubmission.withLock { $0 })
    let owner = try #require(try await harness.pendingRow(message.id))
    let deadline = try #require(owner.ackDeadline)
    #expect(deadline >= submittedAt.addingTimeInterval(ackWindow - schedulingTolerance))
    let packets = await harness.sendPackets()
    #expect(packets.map { $0[2] } == [0, 1, 2, 2, 2, 2])
    let uncertainPacket = try #require(packets.last)
    await harness.service.handleAcknowledgement(
      code: expectedAck(packet: uncertainPacket, text: message.text, sender: senderKey), tripTime: 90
    )
    #expect(try await harness.store.fetchMessage(id: message.id)?.status == .delivered)
  }

  func partialSendHarness(
    script: RadioScript,
    ackGiveUpWindow: TimeInterval = 2,
    poolDelay: TimeInterval = 0.002
  ) async throws -> RadioHarness {
    try await RadioHarness(
      budget: .default,
      senderKey: senderKey,
      ackGiveUpWindow: ackGiveUpWindow,
      poolBackoff: PoolBackoffConfig(baseDelay: poolDelay, exponentBase: 1, jitterRange: 1...1),
      commandTimeout: 0.2,
      transportWaitTimeout: 0.05,
      script: script
    )
  }
}

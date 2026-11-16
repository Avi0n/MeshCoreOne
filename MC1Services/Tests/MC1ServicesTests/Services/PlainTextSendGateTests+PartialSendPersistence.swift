import Foundation
@testable import MC1Services
import os
import Testing

extension PlainTextSendGateTests {
  @Test(arguments: [MessageStatus.delivered, .failed])
  func `a failed owner save resolves the accepted dm without replay`(outcome: MessageStatus) async throws {
    let ackWindow: TimeInterval = 0.4
    let script = RadioScript(senderKey: senderKey, sendReply: { attempt, _ in
      attempt == 1 ? .tableFull : .accepted
    })
    let harness = try await partialSendHarness(script: script, ackGiveUpWindow: ackWindow)
    defer { harness.stop() }
    let failedSave = await failFirstOwnerSave(harness)
    let contact = try await harness.contact(byte: 0xD1)
    let message = try await harness.service.createPendingMessage(text: "owner write failure", to: contact)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: message.id, contactID: contact.id))
    try await waitUntil("the failed owner conversion ends the wire pass") {
      await !harness.queue.isDMDrainingForTesting && failedSave.withLock { $0 }
    }
    let original = try #require(try await harness.pendingRow(message.id))
    #expect(original.ackDeadline == nil)

    if outcome == .delivered {
      let ack = try #require(script.codes().first)
      await harness.service.handleAcknowledgement(code: ack, tripTime: 50)
    } else {
      let tracking = try #require(await harness.service.pendingAckForTest(message.id))
      let deadline = tracking.sentAt.addingTimeInterval(max(ackWindow, tracking.timeout))
      try await waitUntil("the original ACK deadline expires") { Date() > deadline }
    }
    try await harness.service.checkExpiredAcks()
    #expect(try await harness.store.fetchMessage(id: message.id)?.status == outcome)
    #expect(try await harness.pendingRow(message.id) == nil)
    #expect(await harness.gate.ownerLeaseIDForTesting == nil)
    #expect(await harness.sendPackets().map { $0[2] } == [0, 1, 1, 1, 1])
  }

  @Test
  func `a deferred owner save leaves a replacement manual retry untouched`() async throws {
    let script = RadioScript(senderKey: senderKey, sendReply: { attempt, _ in
      attempt == 1 ? .tableFull : .accepted
    })
    let harness = try await partialSendHarness(script: script)
    defer { harness.stop() }
    let failedSave = await failFirstOwnerSave(harness)
    let contact = try await harness.contact(byte: 0xD2)
    let message = try await harness.service.createPendingMessage(text: "replacement retry", to: contact)
    let envelope = DirectMessageEnvelope(messageID: message.id, contactID: contact.id)
    try await harness.queue.enqueueDM(envelope)
    try await waitUntil("the failed owner conversion ends the wire pass") {
      await !harness.queue.isDMDrainingForTesting && failedSave.withLock { $0 }
    }
    _ = try await harness.store.replacePendingSendForRetry(
      messageID: message.id, dto: PendingSendDTO(envelope: envelope, radioID: harness.radioID)
    )
    let replacement = try #require(try await harness.pendingRow(message.id))

    try await harness.service.checkExpiredAcks()
    #expect(try await harness.pendingRow(message.id) == replacement)
    #expect(try await harness.store.fetchMessage(id: message.id)?.status == .pending)
    #expect(await harness.gate.ownerLeaseIDForTesting == nil)
  }

  @Test
  func `an inline send keeps its row when the ack owner save fails`() async throws {
    let script = RadioScript(senderKey: senderKey)
    let harness = try await partialSendHarness(script: script)
    defer { harness.stop() }
    _ = await failFirstOwnerSave(harness)
    let contact = try await harness.contact(byte: 0xD3)
    let message = try await harness.service.sendDirectMessage(text: "inline owner failure", to: contact)
    let row = try #require(try await harness.pendingRow(message.id))
    #expect(row.ackDeadline == nil)

    let ack = try #require(script.codes().first)
    await harness.service.handleAcknowledgement(code: ack, tripTime: 80)
    try await harness.service.checkExpiredAcks()
    #expect(try await harness.store.fetchMessage(id: message.id)?.status == .delivered)
    #expect(try await harness.pendingRow(message.id) == nil)
    #expect(await harness.sendCount() == 1)
  }

  private func failFirstOwnerSave(_ harness: RadioHarness) async -> OSAllocatedUnfairLock<Bool> {
    let failed = OSAllocatedUnfairLock(initialState: false)
    await harness.store.setSavePendingAcknowledgementFaultInjection {
      try failed.withLock { didFail in
        guard !didFail else { return }
        didFail = true
        throw OwnerSaveError.injected
      }
    }
    return failed
  }

  private enum OwnerSaveError: Error {
    case injected
  }
}

@testable import MC1Services
import Testing

extension PlainTextSendGateTests {
  private enum AttemptPersistenceError: Error {
    case unavailable
  }

  @Test
  func `quick reply is saved while it waits for admission and sends once the lease is free`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x41)
    let handler = try quickReplyHandler(harness)
    let owner = try await harness.gate.acquire()
    let reply = Task { await handler.handler.handleQuickReply(contactID: contact.id, text: "from notification") }
    defer { reply.cancel() }
    try await waitUntil("quick reply is waiting for admission") {
      await harness.gate.waiterCountForTesting == 1
    }
    let sendsWhileHeld = await harness.sendCount()
    #expect(sendsWhileHeld == 0)
    let saved = try await harness.store.fetchMessages(contactID: contact.id, limit: 2, offset: 0)
    #expect(saved.map(\.text) == ["from notification"])
    #expect(saved.first?.status == .pending)
    await harness.gate.stopIssuing(owner)
    try await waitUntil("quick reply sends after admission") { await harness.sendCount() == 1 }
    await reply.value
    let messageID = try #require(saved.first?.id)
    #expect(try await harness.store.fetchMessage(id: messageID)?.status == .sent)
    let ackOwner = try await harness.pendingRow(messageID)
    #expect(ackOwner?.ackDeadline != nil)
    #expect(ackOwner?.attemptCount == 1)
  }

  @Test
  func `an invalidated gate keeps a failed quick reply and offers a draft`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x42)
    let handler = try quickReplyHandler(harness)
    let owner = try await harness.gate.acquire()
    let draft = Task { await handler.handler.handleQuickReply(contactID: contact.id, text: "saved draft") }
    try await waitUntil("quick reply is waiting for admission") {
      await harness.gate.waiterCountForTesting == 1
    }
    #expect(await harness.sendCount() == 0)
    await harness.gate.invalidate()
    _ = owner
    await draft.value
    #expect(handler.notifications.consumeDraft(for: contact.id) == "saved draft")
    let saved = try await harness.store.fetchMessages(contactID: contact.id, limit: 2, offset: 0)
    #expect(saved.map(\.text) == ["saved draft"])
    #expect(saved.first?.status == .failed)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.radioID).isEmpty)
  }

  @Test
  func `a quick reply does not transmit when its attempt cannot be persisted`() async throws {
    let harness = try await RadioHarness(budget: .default, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x47)
    let handler = try quickReplyHandler(harness)
    await harness.store.setIncrementPendingSendAttemptCountFaultInjection {
      throw AttemptPersistenceError.unavailable
    }

    await handler.handler.handleQuickReply(contactID: contact.id, text: "saved after write failure")

    #expect(await harness.sendCount() == 0)
    #expect(handler.notifications.consumeDraft(for: contact.id) == "saved after write failure")
    let saved = try await harness.store.fetchMessages(contactID: contact.id, limit: 2, offset: 0)
    #expect(saved.map(\.text) == ["saved after write failure"])
    #expect(saved.first?.status == .failed)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.radioID).isEmpty)
  }

  @Test
  func `deleting a saved inline dm while it waits prevents its send`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x43)
    let owner = try await harness.gate.acquire()
    let send = Task { try await harness.service.sendDirectMessage(text: "removed", to: contact) }
    defer { send.cancel() }
    try await waitUntil("the inline DM is waiting for admission") {
      await harness.gate.waiterCountForTesting == 1
    }
    let messages = try await harness.store.fetchMessages(contactID: contact.id, limit: 1, offset: 0)
    let message = try #require(messages.first)
    try await harness.store.deleteMessage(id: message.id)
    await harness.gate.stopIssuing(owner)

    await #expect(throws: CancellationError.self) { try await send.value }
    #expect(await harness.sendCount() == 0)
    #expect(try await harness.store.fetchMessage(id: message.id) == nil)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.radioID).isEmpty)
  }
}

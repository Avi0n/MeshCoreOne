import Foundation
@testable import MC1Services
@testable import MeshCore
import os
import Testing

extension PlainTextSendGateTests {
  @Test
  func `a quick reply waiting for admission restarts from its pending send and transmits once`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x44)
    let handler = try quickReplyHandler(harness)
    let owner = try await harness.gate.acquire()
    let reply = Task { await handler.handler.handleQuickReply(contactID: contact.id, text: "from notification") }
    var recoveryQueue: ChatSendQueueService?
    var replayQueue: ChatSendQueueService?
    func finish() async {
      await recoveryQueue?.shutdown()
      await replayQueue?.shutdown()
      reply.cancel()
      await harness.gate.invalidate()
      await reply.value
    }

    do {
      try await waitUntil("quick reply is waiting with a recoverable send") {
        guard await harness.gate.waiterCountForTesting == 1, await harness.gate.ownerLeaseIDForTesting == owner.id else { return false }
        let messages = try await harness.store.fetchMessages(contactID: contact.id, limit: 2, offset: 0)
        guard let message = messages.first, message.text == "from notification", message.status == .pending else {
          return false
        }
        let row = try await harness.pendingRow(message.id)
        return row?.ackDeadline == nil && row?.attemptCount == 0 && row?.isResend == false
      }
      #expect(await harness.sendCount() == 0)
      let messageID = try #require(
        try await harness.store.fetchMessages(contactID: contact.id, limit: 1, offset: 0).first?.id
      )
      let saved = try #require(try await harness.pendingRow(messageID))
      #expect(saved.contactID == contact.id)

      let recovery = makeRestartedQueue(harness)
      recoveryQueue = recovery
      await recovery.hydrate()
      try await waitUntil("the restarted queue transmits the saved reply") {
        await harness.sendCount() == 1
      }
      await recovery.awaitDrainCompletion()
      #expect(await harness.gate.waiterCountForTesting == 1)
      #expect(await harness.gate.ownerLeaseIDForTesting == owner.id)
      #expect(await harness.sendCount() == 1)
      #expect(await harness.resetIndex() == nil)
      #expect(try await harness.store.fetchMessage(id: messageID)?.status == .sent)
      #expect(try await harness.store.fetchMessage(id: messageID)?.text == "from notification")
      #expect(try await harness.pendingRow(messageID)?.ackDeadline != nil)
      #expect(handler.notifications.consumeDraft(for: contact.id) == nil)

      let replay = makeRestartedQueue(harness)
      replayQueue = replay
      await replay.hydrate()
      await replay.awaitDrainCompletion()
      #expect(await harness.sendCount() == 1)
      #expect(await harness.gate.waiterCountForTesting == 1)
      await finish()
    } catch {
      await finish()
      throw error
    }
  }

  @Test
  func `an accepted quick reply restarts with the same timestamp and one attempt`() async throws {
    let harness = try await RadioHarness(budget: .default, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x45)
    let handler = try quickReplyHandler(harness)
    let accepted = OSAllocatedUnfairLock(initialState: false)
    let (acceptanceEvents, acceptanceContinuation) = AsyncStream<Void>.makeStream()
    await harness.service.setInlineSendAcceptedGateForTesting {
      accepted.withLock { $0 = true }
      for await _ in acceptanceEvents {}
    }
    harness.script.holdSendMessages(true)
    let reply = Task { await handler.handler.handleQuickReply(contactID: contact.id, text: "accepted reply") }
    var recoveryQueue: ChatSendQueueService?
    var replayQueue: ChatSendQueueService?
    func finish() async {
      await recoveryQueue?.shutdown()
      await replayQueue?.shutdown()
      harness.script.holdSendMessages(false)
      reply.cancel()
      acceptanceContinuation.finish()
      await harness.gate.invalidate()
      await reply.value
    }

    do {
      try await waitUntil("the reply command is on the wire") {
        await harness.sendCount() == 1
      }
      let originalPacket = try #require(await harness.sendPackets().first)
      let originalTimestamp = wireTimestamp(originalPacket)
      let messageID = try #require(
        try await harness.store.fetchMessages(contactID: contact.id, limit: 1, offset: 0).first?.id
      )
      let pending = try #require(try await harness.pendingRow(messageID))
      #expect((pending.attemptCount ?? 0) >= 1)
      #expect(pending.ackDeadline == nil)
      #expect(await harness.sendCount() == 1)

      harness.script.holdSendMessages(false)
      try await waitUntil("the radio accepted the reply before ACK persistence") {
        accepted.withLock { $0 }
      }
      #expect(try await harness.pendingRow(messageID)?.ackDeadline == nil)
      try await waitUntil("recovery starts in a later timestamp second") {
        UInt32(Date().timeIntervalSince1970) > originalTimestamp
      }
      let recovery = makeRestartedQueue(harness)
      recoveryQueue = recovery
      await recovery.hydrate()
      await recovery.awaitDrainCompletion()

      let packets = await harness.sendPackets()
      #expect(packets.count == 2)
      #expect(packets.map(wireTimestamp) == [originalTimestamp, originalTimestamp])
      #expect(await harness.resetIndex() == nil)
      #expect(try await harness.store.fetchMessage(id: messageID)?.status == .sent)
      #expect(try await harness.pendingRow(messageID)?.ackDeadline != nil)
      #expect(handler.notifications.consumeDraft(for: contact.id) == nil)

      let replay = makeRestartedQueue(harness)
      replayQueue = replay
      await replay.hydrate()
      await replay.awaitDrainCompletion()
      #expect(await harness.sendCount() == 2)
      await finish()
    } catch {
      await finish()
      throw error
    }
  }

  @Test(arguments: [MessageStatus.delivered, .failed])
  func `a terminal quick reply is not replayed before its pending send is cleaned up`(
    status: MessageStatus
  ) async throws {
    let harness = try await RadioHarness(budget: .default, senderKey: senderKey)
    defer { harness.stop() }
    let contact = try await harness.contact(byte: 0x46)
    let handler = try quickReplyHandler(harness)
    let accepted = OSAllocatedUnfairLock(initialState: false)
    let (acceptanceEvents, acceptanceContinuation) = AsyncStream<Void>.makeStream()
    await harness.service.setInlineSendAcceptedGateForTesting {
      accepted.withLock { $0 = true }
      for await _ in acceptanceEvents {}
    }
    let text = "terminal reply"
    let reply = Task { await handler.handler.handleQuickReply(contactID: contact.id, text: text) }
    var recoveryQueue: ChatSendQueueService?
    func finish() async {
      await recoveryQueue?.shutdown()
      reply.cancel()
      acceptanceContinuation.finish()
      await harness.gate.invalidate()
      await reply.value
    }

    do {
      try await waitUntil("the radio accepted the reply before ACK persistence") {
        accepted.withLock { $0 }
      }
      let packet = try #require(await harness.sendPackets().first)
      let messageID = try #require(
        try await harness.store.fetchMessages(contactID: contact.id, limit: 1, offset: 0).first?.id
      )
      if status == .delivered {
        await harness.service.handleAcknowledgement(
          code: expectedAck(packet: packet, text: text, sender: senderKey),
          tripTime: 10
        )
      } else {
        try await harness.store.updateMessageStatus(id: messageID, status: status)
      }
      #expect(try await harness.store.fetchMessage(id: messageID)?.status == status)
      let pending = try #require(try await harness.pendingRow(messageID))
      #expect(pending.ackDeadline == nil)

      let recovery = makeRestartedQueue(harness)
      recoveryQueue = recovery
      await recovery.hydrate()
      await recovery.awaitDrainCompletion()

      #expect(await harness.sendCount() == 1)
      #expect(await harness.resetIndex() == nil)
      #expect(try await harness.store.fetchMessage(id: messageID)?.status == status)
      #expect(try await harness.pendingRow(messageID) == nil)
      await finish()
    } catch {
      await finish()
      throw error
    }
  }
}

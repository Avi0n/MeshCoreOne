import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

extension PlainTextSendGateTests {
  @Test
  func `queued exhaustion keeps the next dm off the wire until ack or expiry`() async throws {
    let harness = try await RadioHarness(budget: .default, senderKey: senderKey)
    defer { harness.stop() }
    await harness.service.startEventMonitoring()
    let contactA = try await harness.contact(byte: 0x71)
    let contactB = try await harness.contact(byte: 0x72)
    let messageA = try await harness.service.createPendingMessage(text: "alpha", to: contactA)
    let messageB = try await harness.service.createPendingMessage(text: "beta", to: contactB)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageA.id, contactID: contactA.id))
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageB.id, contactID: contactB.id))
    try await waitUntil("A spends 4 and 4 and B is waiting") {
      await harness.isWaiting(afterSends: 8)
    }
    #expect(harness.script.codes().count == 8)
    let pendingB = try await harness.pendingRow(messageB.id)
    #expect(pendingB?.attemptCount == 0)
    #expect(try await harness.store.fetchMessage(id: messageB.id)?.status == .pending)

    let attemptZero = try #require(harness.script.codes().first)
    await harness.session.dispatchForTesting(.acknowledgement(code: attemptZero, tripTime: 20))
    try await waitUntil("A is delivered and B then sends") {
      let delivered = try await harness.store.fetchMessage(id: messageA.id)?.status == .delivered
      let sends = await harness.sendCount()
      return delivered && sends > 8
    }
    #expect(try await harness.store.fetchMessage(id: messageB.id)?.status != .delivered)
  }

  @Test
  func `queued exhaustion fails A on the expiry path before B can send`() async throws {
    let harness = try await RadioHarness(budget: .default, senderKey: senderKey, ackGiveUpWindow: 0)
    defer { harness.stop() }
    let contactA = try await harness.contact(byte: 0x81)
    let contactB = try await harness.contact(byte: 0x82)
    let messageA = try await harness.service.createPendingMessage(text: "expire-a", to: contactA)
    let messageB = try await harness.service.createPendingMessage(text: "expire-b", to: contactB)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageA.id, contactID: contactA.id))
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageB.id, contactID: contactB.id))
    try await waitUntil("A is exhausted and B is waiting") {
      await harness.isWaiting(afterSends: 8)
    }
    try await waitUntil("A's give-up deadline has elapsed") {
      guard let tracking = await harness.service.pendingAckForTest(messageA.id) else { return false }
      let deadline = tracking.owner?.deadline ?? tracking.sentAt
      return Date() > deadline
    }
    try await harness.service.checkExpiredAcks()
    try await waitUntil("expiry fails A and then admits B") {
      let failed = try await harness.store.fetchMessage(id: messageA.id)?.status == .failed
      let sends = await harness.sendCount()
      return failed && sends > 8
    }
  }

  @Test
  func `identical text at 1 and 0 delivers only the admitted dm`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    await harness.service.startEventMonitoring()
    let contactA = try await harness.contact(byte: 0x91)
    let contactB = try await harness.contact(byte: 0x92)
    let messageA = try await harness.service.createPendingMessage(text: "same", to: contactA)
    let messageB = try await harness.service.createPendingMessage(text: "same", to: contactB)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageA.id, contactID: contactA.id))
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageB.id, contactID: contactB.id))
    try await waitUntil("A finished its single attempt and B is waiting") {
      await harness.isWaiting(afterSends: 1)
    }
    #expect(try await harness.store.fetchMessage(id: messageB.id)?.status == .pending)
    #expect(try await harness.pendingRow(messageB.id)?.attemptCount == 0)
    let code = try #require(harness.script.codes().first)
    await harness.session.dispatchForTesting(.acknowledgement(code: code, tripTime: 11))
    try await waitUntil("A is delivered") {
      try await harness.store.fetchMessage(id: messageA.id)?.status == .delivered
    }
    #expect(try await harness.store.fetchMessage(id: messageB.id)?.status != .delivered)
    try await waitUntil("B sends only after A's ack") { await harness.sendCount() > 1 }
    #expect(try await harness.store.fetchMessage(id: messageB.id)?.status != .delivered)
  }

  @Test(arguments: [false, true])
  func `retiring a waiting dm leaves its row and a fresh queue can send it`(invalidateFirst: Bool) async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    await harness.service.startEventMonitoring()
    let contactA = try await harness.contact(byte: 0xA1)
    let contactB = try await harness.contact(byte: 0xA2)
    let messageA = try await harness.service.createPendingMessage(text: "owner", to: contactA)
    let messageB = try await harness.service.createPendingMessage(text: "queued", to: contactB)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageA.id, contactID: contactA.id))
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageB.id, contactID: contactB.id))
    try await waitUntil("B is blocked on the gate") {
      await harness.isWaiting(afterSends: 1)
    }
    let attemptCount = try await harness.pendingRow(messageB.id)?.attemptCount
    if invalidateFirst {
      await harness.gate.invalidate()
      await harness.queue.awaitDrainCompletion()
      #expect(await harness.queue.isDMDrainingForTesting == false)
    }
    await harness.queue.shutdown()
    let afterCancel = try await harness.pendingRow(messageB.id)
    #expect(afterCancel?.attemptCount == attemptCount)
    #expect(try await harness.store.fetchMessage(id: messageB.id)?.status == .pending)

    let code = try #require(harness.script.codes().first)
    await harness.session.dispatchForTesting(.acknowledgement(code: code, tripTime: 8))
    try await waitUntil("the owner still reaches delivered") {
      try await harness.store.fetchMessage(id: messageA.id)?.status == .delivered
    }

    let freshGate = PlainTextSendGate()
    let freshService = MessageService(
      session: harness.session,
      dataStore: harness.store,
      contactService: nil,
      dmSendBudget: { .singleAttempt },
      plainTextSendGate: freshGate
    )
    let freshQueue = ChatSendQueueService(
      radioID: harness.radioID,
      dataStore: harness.store,
      messageService: freshService,
      channelService: harness.channelService,
      reactionService: ReactionService(),
      plainTextSendGate: freshGate
    )
    await freshQueue.hydrate()
    try await waitUntil("the fresh queue sends the persisted dm") { await harness.sendCount() > 1 }
    await freshQueue.shutdown()
  }

  @Test
  func `clearing a waiting dm sends no command after it is admitted`() async throws {
    let harness = try await RadioHarness(budget: .singleAttempt, senderKey: senderKey)
    defer { harness.stop() }
    await harness.service.startEventMonitoring()
    let contactA = try await harness.contact(byte: 0xB1)
    let contactB = try await harness.contact(byte: 0xB2)
    let messageA = try await harness.service.createPendingMessage(text: "keep", to: contactA)
    let messageB = try await harness.service.createPendingMessage(text: "cleared", to: contactB)
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageA.id, contactID: contactA.id))
    try await harness.queue.enqueueDM(DirectMessageEnvelope(messageID: messageB.id, contactID: contactB.id))
    try await waitUntil("B is waiting") {
      await harness.isWaiting(afterSends: 1)
    }
    try await harness.store.deletePendingSendsForMessage(messageID: messageB.id)
    let code = try #require(harness.script.codes().first)
    await harness.session.dispatchForTesting(.acknowledgement(code: code, tripTime: 6))
    try await waitUntil("A is delivered and the queue finishes") {
      let delivered = try await harness.store.fetchMessage(id: messageA.id)?.status == .delivered
      let waiting = await harness.gate.waiterCountForTesting
      return delivered && waiting == 0
    }
    try await Task.sleep(for: .milliseconds(50))
    #expect(await harness.sendCount() == 1)
  }
}

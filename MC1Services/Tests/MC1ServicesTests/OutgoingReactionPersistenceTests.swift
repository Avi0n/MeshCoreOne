import Foundation
@testable import MC1Services
import Testing

@Suite("Outgoing reaction persistence")
struct OutgoingReactionPersistenceTests {
  @Test(arguments: [false, true])
  func `concurrent reaction admission creates one wire message and durable send without a badge`(isDM: Bool) async throws {
    let harness = try await makeHarness(isDM: isDM)
    let first = harness.outgoing()
    let second = harness.outgoing()

    async let firstAdmission = harness.store.admitOutgoingReaction(first)
    async let secondAdmission = harness.store.admitOutgoingReaction(second)
    let admissions = try await [firstAdmission, secondAdmission]

    let admitted = try #require(admissions.compactMap(\.self).first)
    #expect(admissions.compactMap(\.self).count == 1)
    let pending = try await harness.store.fetchPendingSends(radioID: harness.target.radioID)
    #expect(pending.count == 1)
    #expect(pending.first?.messageID == admitted.id)
    #expect(pending.first?.attemptCount == 0)
    #expect(pending.first?.isSingleAttempt == isDM)
    #expect(try await harness.store.fetchMessage(id: admitted.id)?.reactionTargetMessageID == harness.target.id)
    #expect(try await harness.store.fetchMessage(id: admitted.id)?.reactionSenderName == "Nate")
    #expect(try await harness.store.fetchReactions(for: harness.target.id).isEmpty)
    #expect(try await harness.store.fetchMessage(id: harness.target.id)?.reactionSummary == nil)
  }

  @Test(arguments: [false, true])
  func `only an accepted reaction materializes one badge and prevents a duplicate send`(isDM: Bool) async throws {
    let harness = try await makeHarness(isDM: isDM)
    let outgoing = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))
    #expect(try await harness.store.completeOutgoingReaction(messageID: outgoing.id) == nil)

    try await harness.store.updateMessageStatus(id: outgoing.id, status: .sent)

    let reaction = try #require(try await harness.store.fetchReactions(for: harness.target.id).first)
    #expect(reaction.senderName == "Nate")
    #expect(reaction.emoji == "👍")
    #expect(reaction.rawText == outgoing.text)
    #expect(try await harness.store.fetchMessage(id: harness.target.id)?.reactionSummary == "👍:1")
    #expect(try await harness.store.completeOutgoingReaction(messageID: outgoing.id)?.inserted == false)
    #expect(try await harness.store.admitOutgoingReaction(harness.outgoing()) == nil)
    #expect(try await harness.store.fetchReactions(for: harness.target.id).count == 1)
  }

  @Test
  func `ACK owner conversion commits the badge and a fast delivered ACK keeps it idempotent`() async throws {
    let harness = try await makeHarness(isDM: true)
    let outgoing = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))
    let pending = try #require(try await harness.store.fetchPendingSends(radioID: outgoing.radioID).first)
    let sentAt = Date()
    let deadline = sentAt.addingTimeInterval(60)
    let owner = try PendingSendDTO(
      id: pending.id,
      envelope: DirectMessageEnvelope(messageID: outgoing.id, contactID: #require(outgoing.contactID)),
      radioID: outgoing.radioID, ackCodes: [Data([1, 2, 3, 4])], ackSentAt: sentAt, ackDeadline: deadline
    )

    #expect(try await harness.store.savePendingAcknowledgement(owner, replacingPendingSendID: pending.id))
    #expect(try await harness.store.fetchMessage(id: outgoing.id)?.status == .sent)
    #expect(try await harness.store.fetchMessage(id: harness.target.id)?.reactionSummary == "👍:1")
    #expect(try await harness.store.fetchPendingSends(radioID: outgoing.radioID).first?.ackDeadline == deadline)
    #expect(try await harness.store.resolvePendingAcknowledgement(
      id: owner.id, expectedDeadline: deadline, status: .delivered, ackCode: 0x0403_0201, roundTripTime: 123
    ))
    #expect(try await harness.store.fetchPendingSends(radioID: outgoing.radioID).isEmpty)
    #expect(try await harness.store.fetchReactions(for: harness.target.id).count == 1)
    #expect(try await harness.store.completeOutgoingReaction(messageID: outgoing.id)?.summary == "👍:1")
  }

  @Test
  func `a delivered ACK before owner conversion atomically materializes the badge`() async throws {
    let harness = try await makeHarness(isDM: true)
    let outgoing = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))

    try await harness.store.updateMessageAck(id: outgoing.id, ackCode: 0x0403_0201, status: .delivered)

    #expect(try await harness.store.fetchMessage(id: outgoing.id)?.status == .delivered)
    #expect(try await harness.store.fetchMessage(id: harness.target.id)?.reactionSummary == "👍:1")
    #expect(try await harness.store.completeOutgoingReaction(messageID: outgoing.id)?.inserted == false)
    #expect(try await harness.store.fetchReactions(for: harness.target.id).count == 1)
  }

  @Test
  func `a failed ACK owner save rolls back status and badge together and can retry locally`() async throws {
    let harness = try await makeHarness(isDM: true)
    let outgoing = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))
    let pending = try #require(try await harness.store.fetchPendingSends(radioID: outgoing.radioID).first)
    let sentAt = Date()
    let owner = try PendingSendDTO(
      id: pending.id,
      envelope: DirectMessageEnvelope(messageID: outgoing.id, contactID: #require(outgoing.contactID)),
      radioID: outgoing.radioID, ackCodes: [Data([1, 2, 3, 4])],
      ackSentAt: sentAt, ackDeadline: sentAt.addingTimeInterval(60)
    )
    await harness.store.setSavePendingAcknowledgementFaultInjection { throw PersistenceFailure.save }

    await #expect(throws: PersistenceFailure.self) {
      try await harness.store.savePendingAcknowledgement(owner, replacingPendingSendID: pending.id)
    }

    #expect(try await harness.store.fetchMessage(id: outgoing.id)?.status == .pending)
    #expect(try await harness.store.fetchReactions(for: harness.target.id).isEmpty)
    #expect(try await harness.store.fetchMessage(id: harness.target.id)?.reactionSummary == nil)
    #expect(try await harness.store.fetchPendingSends(radioID: outgoing.radioID).first?.ackDeadline == nil)
    await harness.store.setSavePendingAcknowledgementFaultInjection(nil)
    #expect(try await harness.store.savePendingAcknowledgement(owner, replacingPendingSendID: pending.id))
    #expect(try await harness.store.fetchMessage(id: outgoing.id)?.status == .sent)
    #expect(try await harness.store.fetchMessage(id: harness.target.id)?.reactionSummary == "👍:1")
  }

  @Test(arguments: [false, true])
  func `terminal failure admits a fresh reaction but active durable work deduplicates it`(isDM: Bool) async throws {
    let harness = try await makeHarness(isDM: isDM)
    let outgoing = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))
    try await harness.store.updateMessageStatus(id: outgoing.id, status: .failed)
    #expect(try await harness.store.admitOutgoingReaction(harness.outgoing()) == nil)
    try await harness.store.deletePendingSendsForMessage(messageID: outgoing.id)

    #expect(try await harness.store.admitOutgoingReaction(harness.outgoing()) != nil)
    #expect(try await harness.store.fetchReactions(for: harness.target.id).isEmpty)
  }

  @Test(arguments: [false, true])
  func `deleting a target removes its queued reaction and completion cannot resurrect it`(isDM: Bool) async throws {
    let harness = try await makeHarness(isDM: isDM)
    let outgoing = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))

    let deletedCount = try await harness.store.deleteMessageAndCount(id: harness.target.id)

    #expect(deletedCount == 2)
    #expect(try await harness.store.fetchMessage(id: outgoing.id) == nil)
    #expect(try await harness.store.fetchPendingSends(radioID: outgoing.radioID).isEmpty)
    #expect(try await harness.store.completeOutgoingReaction(messageID: outgoing.id) == nil)
    #expect(try await harness.store.fetchReactions(for: harness.target.id).isEmpty)
  }

  @Test
  func `deleting a channel sender also removes reactions targeting that sender`() async throws {
    let harness = try await makeHarness(isDM: false)
    let outgoing = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))

    try await harness.store.deleteChannelMessages(fromSender: "Ada", radioID: outgoing.radioID)

    #expect(try await harness.store.fetchMessage(id: outgoing.id) == nil)
    #expect(try await harness.store.fetchPendingSends(radioID: outgoing.radioID).isEmpty)
  }

  @Test(arguments: [false, true])
  func `deleting a target also removes reactions and queued sends beneath a failed reaction`(isDM: Bool) async throws {
    let harness = try await makeHarness(isDM: isDM)
    let failed = try #require(try await harness.store.admitOutgoingReaction(harness.outgoing()))
    try await harness.store.updateMessageStatus(id: failed.id, status: .failed)
    try await harness.store.deletePendingSendsForMessage(messageID: failed.id)
    let failedHarness = Harness(store: harness.store, target: failed)
    let nested = try #require(try await harness.store.admitOutgoingReaction(failedHarness.outgoing(emoji: "👀")))
    _ = try await harness.store.admitReaction(ReactionDTO(
      messageID: failed.id, emoji: "❤️", senderName: "Bea", messageHash: "reactionhash", rawText: "reaction",
      channelIndex: failed.channelIndex, contactID: failed.contactID, radioID: failed.radioID
    ))
    try await harness.store.saveMessageRepeat(.testRepeat(messageID: failed.id))
    try await harness.store.saveMessageRepeat(.testRepeat(messageID: nested.id))

    let deletedCount = try await harness.store.deleteMessageAndCount(id: harness.target.id)

    #expect(deletedCount == 3)
    #expect(try await harness.store.fetchMessage(id: failed.id) == nil)
    #expect(try await harness.store.fetchMessage(id: nested.id) == nil)
    #expect(try await harness.store.fetchPendingSends(radioID: failed.radioID).isEmpty)
    #expect(try await harness.store.fetchReactions(for: failed.id).isEmpty)
    #expect(try await harness.store.fetchMessageRepeats(messageID: failed.id).isEmpty)
    #expect(try await harness.store.fetchMessageRepeats(messageID: nested.id).isEmpty)
    #expect(try await harness.store.completeOutgoingReaction(messageID: nested.id) == nil)
  }

  @Test(arguments: [false, true])
  func `admission rejects a stale or differently scoped target`(isDM: Bool) async throws {
    let harness = try await makeHarness(isDM: isDM)
    var outgoing = harness.outgoing()
    outgoing.radioID = UUID()
    #expect(try await harness.store.admitOutgoingReaction(outgoing) == nil)
    outgoing = harness.outgoing()
    outgoing.text = harness.outgoing(emoji: "👀").text.replacingOccurrences(of: "\n", with: "invalid\n")
    #expect(try await harness.store.admitOutgoingReaction(outgoing) == nil)
    try await harness.store.deleteMessage(id: harness.target.id)
    #expect(try await harness.store.admitOutgoingReaction(harness.outgoing()) == nil)
    #expect(try await harness.store.fetchPendingSends(radioID: harness.target.radioID).isEmpty)
  }

  private func makeHarness(isDM: Bool) async throws -> Harness {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let target: MessageDTO
    if isDM {
      let contact = ContactDTO.testContact(radioID: radioID)
      try await store.saveContact(contact)
      target = .testDirectMessage(radioID: radioID, contactID: contact.id, text: "Target", direction: .incoming)
    } else {
      let channel = ChannelDTO.testChannel(radioID: radioID)
      try await store.saveChannel(channel)
      target = .testChannelMessage(
        radioID: radioID, channelIndex: channel.index, text: "Target", direction: .incoming, senderNodeName: "Ada"
      )
    }
    try await store.saveMessage(target)
    return Harness(store: store, target: target)
  }

  private struct Harness {
    let store: PersistenceStore
    let target: MessageDTO

    func outgoing(emoji: String = "👍") -> MessageDTO {
      let service = ReactionService()
      let text: String = if target.channelIndex != nil {
        service.buildReactionText(
          emoji: emoji, targetSender: target.isOutgoing ? "Nate" : "Ada",
          targetText: target.text, targetTimestamp: target.reactionTimestamp
        )
      } else {
        service.buildDMReactionText(emoji: emoji, targetText: target.text, targetTimestamp: target.reactionTimestamp)
      }
      return MessageDTO(from: Message(
        radioID: target.radioID, contactID: target.contactID, channelIndex: target.channelIndex,
        text: text, reactionTargetMessageID: target.id, reactionSenderName: "Nate"
      ))
    }
  }

  private enum PersistenceFailure: Error {
    case save
  }
}

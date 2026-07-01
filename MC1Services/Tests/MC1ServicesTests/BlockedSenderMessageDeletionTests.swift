import Foundation
@testable import MC1Services
import SwiftData
import Testing

@Suite("Blocked Sender Message Deletion Tests")
struct BlockedSenderMessageDeletionTests {
  // MARK: - Test Helpers

  private func createTestStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private let radioID = UUID()

  private func makeChannelMessage(
    radioID: UUID,
    senderName: String,
    channelIndex: UInt8 = 0,
    text: String = "test"
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: nil,
      channelIndex: channelIndex,
      text: text,
      timestamp: UInt32(Date().timeIntervalSince1970),
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: senderName,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  private func makeDirectMessage(
    radioID: UUID,
    senderName: String,
    contactID: UUID = UUID()
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: nil,
      text: "dm",
      timestamp: UInt32(Date().timeIntervalSince1970),
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: senderName,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  // MARK: - Tests

  @Test
  func `Deletes all channel messages from a named sender`() async throws {
    let store = try await createTestStore()

    let spam1 = makeChannelMessage(radioID: radioID, senderName: "Spammer", channelIndex: 0)
    let spam2 = makeChannelMessage(radioID: radioID, senderName: "Spammer", channelIndex: 1)
    let legit = makeChannelMessage(radioID: radioID, senderName: "Legit", channelIndex: 0)

    try await store.saveMessage(spam1)
    try await store.saveMessage(spam2)
    try await store.saveMessage(legit)

    try await store.deleteChannelMessages(fromSender: "Spammer", radioID: radioID)

    let remainingSpam1 = try await store.fetchMessage(id: spam1.id)
    let remainingSpam2 = try await store.fetchMessage(id: spam2.id)
    let remainingLegit = try await store.fetchMessage(id: legit.id)

    #expect(remainingSpam1 == nil)
    #expect(remainingSpam2 == nil)
    #expect(remainingLegit != nil)
  }

  @Test
  func `Does not delete DMs from the same sender`() async throws {
    let store = try await createTestStore()

    let channelMsg = makeChannelMessage(radioID: radioID, senderName: "Spammer")
    let dmMsg = makeDirectMessage(radioID: radioID, senderName: "Spammer")

    try await store.saveMessage(channelMsg)
    try await store.saveMessage(dmMsg)

    try await store.deleteChannelMessages(fromSender: "Spammer", radioID: radioID)

    let remainingChannel = try await store.fetchMessage(id: channelMsg.id)
    let remainingDM = try await store.fetchMessage(id: dmMsg.id)

    #expect(remainingChannel == nil)
    #expect(remainingDM != nil)
  }

  @Test
  func `Scopes deletion to the specified device`() async throws {
    let store = try await createTestStore()

    let otherDevice = UUID()
    let msg1 = makeChannelMessage(radioID: radioID, senderName: "Spammer")
    let msg2 = makeChannelMessage(radioID: otherDevice, senderName: "Spammer")

    try await store.saveMessage(msg1)
    try await store.saveMessage(msg2)

    try await store.deleteChannelMessages(fromSender: "Spammer", radioID: radioID)

    let remaining1 = try await store.fetchMessage(id: msg1.id)
    let remaining2 = try await store.fetchMessage(id: msg2.id)

    #expect(remaining1 == nil)
    #expect(remaining2 != nil)
  }

  @Test
  func `No-op when sender has no messages`() async throws {
    let store = try await createTestStore()

    let legit = makeChannelMessage(radioID: radioID, senderName: "Legit")
    try await store.saveMessage(legit)

    try await store.deleteChannelMessages(fromSender: "Ghost", radioID: radioID)

    let remaining = try await store.fetchMessage(id: legit.id)
    #expect(remaining != nil)
  }

  @Test
  func `Deletes reactions associated with deleted channel messages`() async throws {
    let store = try await createTestStore()

    let spamMsg = makeChannelMessage(radioID: radioID, senderName: "Spammer")
    let legitMsg = makeChannelMessage(radioID: radioID, senderName: "Legit")
    try await store.saveMessage(spamMsg)
    try await store.saveMessage(legitMsg)

    // Add reactions to both messages (from other users)
    let reactionOnSpam = ReactionDTO(
      messageID: spamMsg.id,
      emoji: "👍",
      senderName: "Reactor",
      messageHash: "AABB1122",
      rawText: ":thumbsup:",
      channelIndex: 0,
      radioID: radioID
    )
    let reactionOnLegit = ReactionDTO(
      messageID: legitMsg.id,
      emoji: "❤️",
      senderName: "Reactor",
      messageHash: "CCDD3344",
      rawText: ":heart:",
      channelIndex: 0,
      radioID: radioID
    )
    try await store.saveReaction(reactionOnSpam)
    try await store.saveReaction(reactionOnLegit)

    try await store.deleteChannelMessages(fromSender: "Spammer", radioID: radioID)

    let reactionsOnSpam = try await store.fetchReactions(for: spamMsg.id)
    let reactionsOnLegit = try await store.fetchReactions(for: legitMsg.id)

    #expect(reactionsOnSpam.isEmpty)
    #expect(reactionsOnLegit.count == 1)
  }

  @Test
  func `Preserves messages with nil senderNodeName`() async throws {
    let store = try await createTestStore()

    // Message with nil senderNodeName (e.g., system message)
    let nilSenderMsg = MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: nil,
      channelIndex: 0,
      text: "system message",
      timestamp: UInt32(Date().timeIntervalSince1970),
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
    try await store.saveMessage(nilSenderMsg)

    try await store.deleteChannelMessages(fromSender: "AnyName", radioID: radioID)

    let remaining = try await store.fetchMessage(id: nilSenderMsg.id)
    #expect(remaining != nil)
  }
}

import Foundation
@testable import MC1Services
import Testing

@Suite("Message Deduplication Tests")
struct MessageDeduplicationTests {
  // MARK: - Fallback Key Format Tests

  @Test
  func `DM fallback key is deterministic`() throws {
    let contactID = try #require(UUID(uuidString: "11111111-1111-1111-1111-111111111111"))
    let key1 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contactID, channelIndex: nil,
      senderNodeName: nil, timestamp: 1_704_067_200, content: "Hello world"
    )
    let key2 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contactID, channelIndex: nil,
      senderNodeName: nil, timestamp: 1_704_067_200, content: "Hello world"
    )
    #expect(key1 == key2)
    #expect(key1.hasPrefix("dm-"))
  }

  @Test
  func `Channel fallback key is deterministic`() {
    let key1 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 0,
      senderNodeName: "Alice", timestamp: 1_704_067_200, content: "Hello channel"
    )
    let key2 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 0,
      senderNodeName: "Alice", timestamp: 1_704_067_200, content: "Hello channel"
    )
    #expect(key1 == key2)
    #expect(key1.hasPrefix("ch-"))
  }

  @Test
  func `DM and channel fallback keys never collide for same content`() throws {
    let contactID = try #require(UUID(uuidString: "11111111-1111-1111-1111-111111111111"))
    let dmKey = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contactID, channelIndex: nil,
      senderNodeName: nil, timestamp: 1_704_067_200, content: "Hello"
    )
    let channelKey = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 0,
      senderNodeName: "Alice", timestamp: 1_704_067_200, content: "Hello"
    )
    #expect(dmKey != channelKey)
  }

  @Test
  func `Different contacts produce different DM fallback keys`() throws {
    let contact1 = try #require(UUID(uuidString: "11111111-1111-1111-1111-111111111111"))
    let contact2 = try #require(UUID(uuidString: "22222222-2222-2222-2222-222222222222"))
    let key1 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contact1, channelIndex: nil,
      senderNodeName: nil, timestamp: 1_704_067_200, content: "Hello"
    )
    let key2 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contact2, channelIndex: nil,
      senderNodeName: nil, timestamp: 1_704_067_200, content: "Hello"
    )
    #expect(key1 != key2)
  }

  @Test
  func `Different channel indices produce different channel fallback keys`() {
    let key1 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 0,
      senderNodeName: "Alice", timestamp: 1_704_067_200, content: "Hello"
    )
    let key2 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 1,
      senderNodeName: "Alice", timestamp: 1_704_067_200, content: "Hello"
    )
    #expect(key1 != key2)
  }

  // MARK: - Retry Dedup Stability

  @Test
  func `DM retry attempts with same content produce identical dedup keys`() throws {
    let contactID = try #require(UUID(uuidString: "11111111-1111-1111-1111-111111111111"))
    let timestamp: UInt32 = 1_704_067_200
    let text = "Hello mesh"

    // Simulate two retry attempts: same contact, timestamp, and text
    let keyAttempt0 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contactID, channelIndex: nil,
      senderNodeName: nil, timestamp: timestamp, content: text
    )
    let keyAttempt1 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: contactID, channelIndex: nil,
      senderNodeName: nil, timestamp: timestamp, content: text
    )
    #expect(keyAttempt0 == keyAttempt1,
            "Retry attempts with the same content must produce identical dedup keys")
  }

  @Test
  func `Channel retry attempts with same content produce identical dedup keys`() {
    let timestamp: UInt32 = 1_704_067_200
    let text = "Hello channel"

    let keyAttempt0 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 2,
      senderNodeName: "Bob", timestamp: timestamp, content: text
    )
    let keyAttempt1 = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 2,
      senderNodeName: "Bob", timestamp: timestamp, content: text
    )
    #expect(keyAttempt0 == keyAttempt1,
            "Channel retry attempts with the same content must produce identical dedup keys")
  }

  // MARK: - isDuplicateMessage via MockPersistenceStore

  @Test
  func `isDuplicateMessage returns false when no matching key exists`() async throws {
    let store = MockPersistenceStore()
    let result = try await store.isDuplicateMessage(deduplicationKey: "test-key", radioID: UUID())
    #expect(result == false)
  }

  @Test
  func `isDuplicateMessage returns true when matching key exists for the same radio`() async throws {
    let store = MockPersistenceStore()
    let radioID = UUID()
    let dto = makeChannelMessageDTO(radioID: radioID, deduplicationKey: "test-key")
    try await store.saveMessage(dto)

    let result = try await store.isDuplicateMessage(deduplicationKey: "test-key", radioID: radioID)
    #expect(result)
  }

  @Test
  func `isDuplicateMessage is scoped per-radio so two companions storing the same channel packet both succeed`() async throws {
    let store = MockPersistenceStore()
    let radioA = UUID()
    let radioB = UUID()
    let sharedKey = SyncCoordinator.fallbackDeduplicationKey(
      contactID: nil, channelIndex: 0,
      senderNodeName: "Alice", timestamp: 1_704_067_200, content: "aaaaa"
    )
    try await store.saveMessage(makeChannelMessageDTO(radioID: radioA, deduplicationKey: sharedKey))

    let duplicateOnA = try await store.isDuplicateMessage(deduplicationKey: sharedKey, radioID: radioA)
    let duplicateOnB = try await store.isDuplicateMessage(deduplicationKey: sharedKey, radioID: radioB)

    #expect(duplicateOnA, "Same radio sees the prior save as a duplicate")
    #expect(duplicateOnB == false,
            "A different companion radio must not inherit radioA's dedup entry — otherwise the second radio's channel view goes blank after 'change device'")
  }

  // MARK: - Helpers

  private func makeChannelMessageDTO(radioID: UUID, deduplicationKey: String) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: nil,
      channelIndex: 0,
      text: "Hello",
      timestamp: 1_704_067_200,
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 1,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: "Alice",
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      deduplicationKey: deduplicationKey
    )
  }
}

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
}

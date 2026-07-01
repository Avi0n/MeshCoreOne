import Foundation
@testable import MC1Services
import Testing

@Suite("MessageLRUCache Tests")
struct MessageLRUCacheTests {
  @Test
  func `Indexes and retrieves message`() async {
    let cache = MessageLRUCache()
    let messageID = UUID()

    await cache.index(
      messageID: messageID,
      channelIndex: 0,
      senderName: "Node",
      text: "Hello",
      timestamp: 1_704_067_200
    )

    let hash = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    let candidates = await cache.lookup(channelIndex: 0, senderName: "Node", messageHash: hash)

    #expect(candidates.count == 1)
    #expect(candidates.first?.messageID == messageID)
    #expect(candidates.first?.text == "Hello")
    #expect(candidates.first?.timestamp == 1_704_067_200)
  }

  @Test
  func `Returns empty array for non-existent message`() async {
    let cache = MessageLRUCache()

    let candidates = await cache.lookup(channelIndex: 0, senderName: "Node", messageHash: "abcd1234")

    #expect(candidates.isEmpty)
  }

  @Test
  func `Evicts oldest key at capacity`() async {
    let cache = MessageLRUCache(capacity: 3)

    // Add 3 messages with different keys
    for i in 0..<3 {
      await cache.index(
        messageID: UUID(),
        channelIndex: 0,
        senderName: "Node\(i)",
        text: "Message \(i)",
        timestamp: UInt32(i)
      )
    }

    let hash0 = ReactionParser.generateMessageHash(text: "Message 0", timestamp: 0)
    let before = await cache.lookup(channelIndex: 0, senderName: "Node0", messageHash: hash0)
    #expect(!before.isEmpty)

    // Add 4th message with different key (should evict first key)
    await cache.index(
      messageID: UUID(),
      channelIndex: 0,
      senderName: "Node3",
      text: "Message 3",
      timestamp: 3
    )

    let after = await cache.lookup(channelIndex: 0, senderName: "Node0", messageHash: hash0)
    #expect(after.isEmpty)
  }

  @Test
  func `Different channels are separate`() async {
    let cache = MessageLRUCache()
    let messageID = UUID()

    await cache.index(
      messageID: messageID,
      channelIndex: 0,
      senderName: "Node",
      text: "Hello",
      timestamp: 1_704_067_200
    )

    let hash = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)

    // Should find on channel 0
    let result0 = await cache.lookup(channelIndex: 0, senderName: "Node", messageHash: hash)
    #expect(result0.first?.messageID == messageID)

    // Should not find on channel 1
    let result1 = await cache.lookup(channelIndex: 1, senderName: "Node", messageHash: hash)
    #expect(result1.isEmpty)
  }

  @Test
  func `Stores multiple candidates per key`() async {
    let cache = MessageLRUCache()
    let id1 = UUID()
    let id2 = UUID()

    // Two messages with same sender but different text that hash to different values
    // won't test collision - instead test re-indexing same message updates it
    await cache.index(
      messageID: id1,
      channelIndex: 0,
      senderName: "Node",
      text: "Hello",
      timestamp: 1_704_067_200
    )

    await cache.index(
      messageID: id2,
      channelIndex: 0,
      senderName: "Node",
      text: "Hello",
      timestamp: 1_704_067_200
    )

    let hash = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    let candidates = await cache.lookup(channelIndex: 0, senderName: "Node", messageHash: hash)

    // Both messages have same hash, so both should be candidates
    #expect(candidates.count == 2)
    #expect(candidates.contains { $0.messageID == id1 })
    #expect(candidates.contains { $0.messageID == id2 })
  }

  @Test
  func `Caps candidates per key`() async {
    let cache = MessageLRUCache(maxCandidatesPerKey: 3)

    // Add 5 messages with same hash (same text/timestamp, different IDs)
    var ids: [UUID] = []
    for _ in 0..<5 {
      let id = UUID()
      ids.append(id)
      await cache.index(
        messageID: id,
        channelIndex: 0,
        senderName: "Node",
        text: "Hello",
        timestamp: 1_704_067_200
      )
    }

    let hash = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    let candidates = await cache.lookup(channelIndex: 0, senderName: "Node", messageHash: hash)

    // Should only keep most recent 3
    #expect(candidates.count == 3)
    // First two should have been evicted
    #expect(!candidates.contains { $0.messageID == ids[0] })
    #expect(!candidates.contains { $0.messageID == ids[1] })
    // Last three should be present
    #expect(candidates.contains { $0.messageID == ids[2] })
    #expect(candidates.contains { $0.messageID == ids[3] })
    #expect(candidates.contains { $0.messageID == ids[4] })
  }

  @Test
  func `Re-indexing same messageID updates instead of duplicating`() async {
    let cache = MessageLRUCache()
    let messageID = UUID()

    await cache.index(
      messageID: messageID,
      channelIndex: 0,
      senderName: "Node",
      text: "Hello",
      timestamp: 1_704_067_200
    )

    // Re-index same message
    await cache.index(
      messageID: messageID,
      channelIndex: 0,
      senderName: "Node",
      text: "Hello",
      timestamp: 1_704_067_200
    )

    let hash = ReactionParser.generateMessageHash(text: "Hello", timestamp: 1_704_067_200)
    let candidates = await cache.lookup(channelIndex: 0, senderName: "Node", messageHash: hash)

    // Should only have one entry, not two
    #expect(candidates.count == 1)
    #expect(candidates.first?.messageID == messageID)
  }
}

import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

/// Covers the single-transaction sync-persistence methods added for BLE sync-speed
/// optimization (`batchSaveChannels` / `batchSaveContacts`). These carry the
/// correctness-critical reconciliation: channel slot placement, stale-slot deletion,
/// capacity pruning, and leaving circuit-breaker-skipped slots untouched.
@Suite("PersistenceStore Batch Sync Tests")
struct PersistenceStoreBatchSyncTests {
  private func secret(_ byte: UInt8) -> Data {
    Data(repeating: byte, count: ProtocolLimits.channelSecretSize)
  }

  private func publicKey(_ byte: UInt8) -> Data {
    Data(repeating: byte, count: ProtocolLimits.publicKeySize)
  }

  private func channelInfo(_ index: UInt8, name: String, secretByte: UInt8) -> ChannelInfo {
    ChannelInfo(index: index, name: name, secret: secret(secretByte))
  }

  private func contactFrame(_ keyByte: UInt8, name: String, flags: UInt8 = 0) -> ContactFrame {
    ContactFrame(
      publicKey: publicKey(keyByte),
      type: .chat,
      flags: flags,
      outPathLength: 0,
      outPath: Data(),
      name: name,
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
  }

  // MARK: - batchSaveChannels

  @Test
  func `batchSaveChannels inserts new and updates existing in one pass`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(1, name: "Old", secretByte: 0x11))

    let result = try await store.batchSaveChannels(
      radioID: radioID,
      configured: [
        channelInfo(1, name: "New", secretByte: 0x99),
        channelInfo(2, name: "Two", secretByte: 0x22)
      ],
      unconfiguredIndices: [],
      pruneBeyond: 8
    )

    #expect(result.map(\.index) == [1, 2])
    let one = try #require(try await store.fetchChannel(radioID: radioID, index: 1))
    #expect(one.name == "New")
    #expect(one.secret == secret(0x99))
    let two = try #require(try await store.fetchChannel(radioID: radioID, index: 2))
    #expect(two.name == "Two")
  }

  @Test
  func `batchSaveChannels deletes stale rows at unconfigured indices`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(1, name: "Keep", secretByte: 0x11))
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(2, name: "Gone", secretByte: 0x22))

    let result = try await store.batchSaveChannels(
      radioID: radioID,
      configured: [channelInfo(1, name: "Keep", secretByte: 0x11)],
      unconfiguredIndices: [2],
      pruneBeyond: 8
    )

    #expect(result.map(\.index) == [1])
    #expect(try await store.fetchChannel(radioID: radioID, index: 2) == nil)
  }

  @Test
  func `batchSaveChannels prunes orphans beyond device capacity`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(1, name: "InRange", secretByte: 0x11))
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(10, name: "Orphan", secretByte: 0xAA))

    let result = try await store.batchSaveChannels(
      radioID: radioID,
      configured: [channelInfo(1, name: "InRange", secretByte: 0x11)],
      unconfiguredIndices: [],
      pruneBeyond: 8
    )

    #expect(result.map(\.index) == [1])
    #expect(try await store.fetchChannel(radioID: radioID, index: 10) == nil)
  }

  @Test
  func `batchSaveChannels leaves circuit-breaker-skipped slots untouched`() async throws {
    // Simulates a sync that read index 1, then the circuit breaker aborted, so indices
    // 2 and 3 were never queried — they are in neither the configured nor the
    // unconfigured list and must survive the persist pass.
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(1, name: "One", secretByte: 0x11))
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(2, name: "Two", secretByte: 0x22))
    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(3, name: "Three", secretByte: 0x33))

    let result = try await store.batchSaveChannels(
      radioID: radioID,
      configured: [channelInfo(1, name: "OneUpdated", secretByte: 0x11)],
      unconfiguredIndices: [],
      pruneBeyond: 8
    )

    #expect(result.map(\.index) == [1, 2, 3])
    #expect(try await store.fetchChannel(radioID: radioID, index: 1)?.name == "OneUpdated")
    #expect(try await store.fetchChannel(radioID: radioID, index: 2)?.name == "Two")
    #expect(try await store.fetchChannel(radioID: radioID, index: 3)?.name == "Three")
  }

  @Test
  func `batchSaveChannels handles non-contiguous configured indices`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)

    let result = try await store.batchSaveChannels(
      radioID: radioID,
      configured: [
        channelInfo(0, name: "Public", secretByte: 0x00),
        channelInfo(2, name: "Two", secretByte: 0x22),
        channelInfo(7, name: "Seven", secretByte: 0x77)
      ],
      unconfiguredIndices: [1, 3, 4, 5, 6],
      pruneBeyond: 8
    )

    #expect(result.map(\.index) == [0, 2, 7])
    #expect(try await store.fetchChannel(radioID: radioID, index: 7)?.name == "Seven")
  }

  @Test
  func `batchSaveChannels on empty store with no work returns empty`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)

    let result = try await store.batchSaveChannels(
      radioID: radioID,
      configured: [],
      unconfiguredIndices: [],
      pruneBeyond: 8
    )

    #expect(result.isEmpty)
  }

  @Test
  func `batchSaveChannels scopes to radioID`() async throws {
    let radioA = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioA, maxChannels: 8)
    let radioB = UUID()
    _ = try await store.saveChannel(radioID: radioB, from: channelInfo(1, name: "OtherRadio", secretByte: 0x55))

    _ = try await store.batchSaveChannels(
      radioID: radioA,
      configured: [channelInfo(1, name: "Mine", secretByte: 0x11)],
      unconfiguredIndices: [],
      pruneBeyond: 8
    )

    // Radio B's channel must be unaffected by a Radio A sync.
    #expect(try await store.fetchChannel(radioID: radioB, index: 1)?.name == "OtherRadio")
  }

  // MARK: - Slot occupant change wipes history

  private func seedSlotHistory(
    _ store: PersistenceStore, radioID: UUID, index: UInt8, secretByte: UInt8, count: Int = 3
  ) async throws -> UUID {
    let channelID = try await store.saveChannel(
      radioID: radioID, from: channelInfo(index, name: "Slot\(index)", secretByte: secretByte)
    )
    for i in 0..<count {
      let messageID = UUID()
      try await store.saveMessage(.testChannelMessage(
        id: messageID, radioID: radioID, channelIndex: index, text: "msg \(i)", direction: .incoming
      ))
      try await store.saveReaction(ReactionDTO(
        messageID: messageID, emoji: "👍", senderName: "peer", messageHash: "h\(i)",
        rawText: "👍 h\(i)", channelIndex: index, radioID: radioID
      ))
      try await store.incrementChannelUnreadCount(channelID: channelID)
    }
    return channelID
  }

  @Test
  func `deleteChannel removes the slot's messages and reactions, sparing other slots`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    let doomedID = try await seedSlotHistory(store, radioID: radioID, index: 3, secretByte: 0x33)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 5, secretByte: 0x55, count: 1)
    let doomedMessageIDs = try await store.fetchMessages(radioID: radioID, channelIndex: 3, limit: 10).map(\.id)

    try await store.deleteChannel(id: doomedID)

    #expect(try await store.fetchChannel(radioID: radioID, index: 3) == nil)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 3, limit: 10).isEmpty)
    for id in doomedMessageIDs {
      #expect(try await store.fetchReactions(for: id).isEmpty)
    }
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 5, limit: 10).count == 1)
  }

  @Test
  func `saveChannel with a new secret wipes the slot and resets derived counters`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 3, secretByte: 0x33)

    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(3, name: "Replacement", secretByte: 0x99))

    let row = try #require(try await store.fetchChannel(radioID: radioID, index: 3))
    #expect(row.name == "Replacement")
    #expect(row.unreadCount == 0)
    #expect(row.lastMessageDate == nil)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 3, limit: 10).isEmpty)
  }

  @Test
  func `saveChannel with the same secret keeps history on rename`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 3, secretByte: 0x33)

    _ = try await store.saveChannel(radioID: radioID, from: channelInfo(3, name: "Renamed", secretByte: 0x33))

    let row = try #require(try await store.fetchChannel(radioID: radioID, index: 3))
    #expect(row.name == "Renamed")
    #expect(row.unreadCount == 3)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 3, limit: 10).count == 3)
  }

  @Test
  func `batchSaveChannels wipes secret-changed, unconfigured, and pruned slots and keeps the rest`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 1, secretByte: 0x11)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 2, secretByte: 0x22)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 3, secretByte: 0x33)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 10, secretByte: 0xAA)
    // Leftover from an earlier prune: messages at a slot with no row.
    try await store.saveMessage(.testChannelMessage(radioID: radioID, channelIndex: 4, text: "leftover"))

    _ = try await store.batchSaveChannels(
      radioID: radioID,
      configured: [
        channelInfo(1, name: "Renamed", secretByte: 0x11),
        channelInfo(2, name: "Taken over", secretByte: 0x99)
      ],
      unconfiguredIndices: [3, 4],
      pruneBeyond: 8
    )

    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 1, limit: 10).count == 3)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 2, limit: 10).isEmpty)
    #expect(try await store.fetchChannel(radioID: radioID, index: 2)?.unreadCount == 0)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 3, limit: 10).isEmpty)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 4, limit: 10).isEmpty)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 10, limit: 10).isEmpty)
  }

  @Test
  func `batchSaveChannels failed save restores the wiped messages with the rows`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 2, secretByte: 0x22)
    _ = try await seedSlotHistory(store, radioID: radioID, index: 3, secretByte: 0x33)

    await store.setBatchSaveChannelsFaultInjection { throw PersistenceStoreError.saveFailed("test") }
    await #expect(throws: PersistenceStoreError.self) {
      _ = try await store.batchSaveChannels(
        radioID: radioID,
        configured: [channelInfo(2, name: "Taken over", secretByte: 0x99)],
        unconfiguredIndices: [3],
        pruneBeyond: 8
      )
    }
    await store.setBatchSaveChannelsFaultInjection(nil)

    #expect(try await store.fetchChannel(radioID: radioID, index: 2)?.secret == secret(0x22))
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 2, limit: 10).count == 3)
    #expect(try await store.fetchChannel(radioID: radioID, index: 3) != nil)
    #expect(try await store.fetchMessages(radioID: radioID, channelIndex: 3, limit: 10).count == 3)
  }

  // MARK: - batchSaveContacts

  @Test
  func `batchSaveContacts inserts all and returns count`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)

    let count = try await store.batchSaveContacts(radioID: radioID, from: [
      contactFrame(0x01, name: "Alice"),
      contactFrame(0x02, name: "Bob"),
      contactFrame(0x03, name: "Carol")
    ])

    #expect(count == 3)
    let stored = try await store.fetchContacts(radioID: radioID)
    #expect(stored.count == 3)
    #expect(Set(stored.map(\.name)) == ["Alice", "Bob", "Carol"])
  }

  @Test
  func `batchSaveContacts updates existing rows and preserves favorite bit`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)

    // Seed a favorited contact (flags bit 0 set).
    _ = try await store.saveContact(radioID: radioID, from: contactFrame(0x01, name: "Original", flags: 0x01))

    // Re-sync with a frame whose flags clear bit 0; update(from:) must keep the favorite.
    let count = try await store.batchSaveContacts(radioID: radioID, from: [
      contactFrame(0x01, name: "Renamed", flags: 0x00)
    ])

    #expect(count == 1)
    let stored = try await store.fetchContacts(radioID: radioID)
    #expect(stored.count == 1)
    let contact = try #require(stored.first)
    #expect(contact.name == "Renamed")
    #expect(contact.isFavorite)
  }

  @Test
  func `batchSaveContacts preserves existing avatarImageData`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)
    let jpeg = Data(repeating: 0xAB, count: 32)
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: publicKey(0x01),
      name: "Original",
      avatarImageData: jpeg
    )
    try await store.saveContact(contact)

    _ = try await store.batchSaveContacts(radioID: radioID, from: [
      contactFrame(0x01, name: "Renamed")
    ])

    let stored = try #require(try await store.fetchContact(radioID: radioID, publicKey: publicKey(0x01)))
    #expect(stored.name == "Renamed")
    #expect(stored.avatarImageData == jpeg)
  }

  @Test
  func `batchSaveContacts with empty frames returns zero`() async throws {
    let radioID = UUID()
    let store = try await PersistenceStore.createTestDataStore(radioID: radioID, maxChannels: 8)

    let count = try await store.batchSaveContacts(radioID: radioID, from: [])
    #expect(count == 0)
    #expect(try await store.fetchContacts(radioID: radioID).isEmpty)
  }
}

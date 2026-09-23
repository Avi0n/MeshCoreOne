import Foundation
@testable import MC1Services
import Testing

/// `saveMessage` does not persist the link-preview or `reactionSummary` columns, and
/// the wire `ChannelInfo` carries no notification/favorite state. These verify the
/// seed's dedicated mutators (and the DTO-based `saveChannel`) write those through.
@MainActor
struct SimulatorSeedTests {
  private func seededStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    try await SimulatorConnectionMode().seedDataStore(store)
    return store
  }

  private let radioID = MockDataProvider.simulatorDeviceID

  @Test
  func `link preview columns land`() async throws {
    let store = try await seededStore()
    let message = try await store.fetchMessage(id: MockDataProvider.aliceLinkPreviewMessageID)
    let unwrapped = try #require(message)
    #expect(unwrapped.linkPreviewTitle == "Skyline Ridge Trail Guide")
    #expect(unwrapped.linkPreviewFetched == true)
    let imageData = try #require(unwrapped.linkPreviewImageData)
    #expect(!imageData.isEmpty)
  }

  @Test
  func `reaction summary and rows land`() async throws {
    let store = try await seededStore()

    let dmMessage = try #require(try await store.fetchMessage(id: MockDataProvider.aliceReactedMessageID))
    #expect(dmMessage.reactionSummary == "👍:2,❤️:1")
    let dmReactions = try await store.fetchReactions(for: MockDataProvider.aliceReactedMessageID)
    #expect(dmReactions.count == 3)

    let channelMessage = try #require(try await store.fetchMessage(id: MockDataProvider.bayAreaReactedMessageID))
    #expect(channelMessage.reactionSummary == "🎉:2")
    let channelReactions = try await store.fetchReactions(for: MockDataProvider.bayAreaReactedMessageID)
    #expect(channelReactions.count == 2)
  }

  @Test
  func `channel notification state lands`() async throws {
    let store = try await seededStore()
    let channels = try await store.fetchChannels(radioID: radioID)

    let muted = try #require(channels.first { $0.index == MockDataProvider.trailCrewChannelIndex })
    #expect(muted.notificationLevel == .muted)

    let favorite = try #require(channels.first { $0.index == MockDataProvider.bayAreaChannelIndex })
    #expect(favorite.isFavorite)
  }

  @Test
  func `heard repeats land`() async throws {
    let store = try await seededStore()
    let repeats = try await store.fetchMessageRepeats(messageID: MockDataProvider.frankRepeatMessageID)
    #expect(repeats.count == 3)
  }

  @Test
  func `incoming extra paths land on located repeater prefixes`() async throws {
    let store = try await seededStore()
    let contacts = try await store.fetchContacts(radioID: radioID)
    let repeaterPrefixes = Set(
      contacts
        .filter { $0.type == .repeater && $0.hasLocation }
        .map { Data($0.publicKey.prefix(1)) }
    )
    let seeds: [UInt8] = [
      MockDataProvider.northRidgeRepeaterSeed,
      MockDataProvider.twinPeaksRepeaterSeed,
      MockDataProvider.oaklandRepeaterSeed
    ]
    for seed in seeds {
      #expect(repeaterPrefixes.contains(Data([seed])))
    }

    for messageID in [MockDataProvider.aliceMultiPathMessageID, MockDataProvider.publicMultiPathMessageID] {
      let message = try #require(try await store.fetchMessage(id: messageID))
      #expect(message.isOutgoing == false)
      #expect(message.heardRepeats == MockDataProvider.incomingExtraPathCount)
      #expect(message.pathNodes == MockDataProvider.incomingFirstPath)
      #expect(message.hopCount == 2)
      let repeats = try await store.fetchMessageRepeats(messageID: messageID)
      #expect(repeats.count == MockDataProvider.incomingExtraPathCount)
      #expect(Set(repeats.map(\.hopCount)) == [1, 2, 3])
      let hopBytes = repeats.flatMap(\.pathNodes)
      for byte in hopBytes {
        #expect(repeaterPrefixes.contains(Data([byte])))
      }
    }
  }

  @Test
  func `flood route fields round trip through save message`() async throws {
    let store = try await seededStore()
    let message = try #require(try await store.fetchMessage(id: MockDataProvider.frankFloodUniqueMessageID))
    #expect(message.routeType == .tcFlood)
    #expect(message.regionScope == MockDataProvider.uniqueRegionName)
    #expect(message.regionScopeMatches == [MockDataProvider.uniqueRegionName])
  }

  @Test
  func `ambiguous flood dual fields round trip`() async throws {
    let store = try await seededStore()

    let dm = try #require(try await store.fetchMessage(id: MockDataProvider.frankFloodAmbiguousMessageID))
    #expect(dm.routeType == .tcFlood)
    #expect(dm.regionScope == nil)
    #expect(dm.regionScopeMatches == MockDataProvider.ambiguousRegionNames)

    let channel = try #require(try await store.fetchMessage(id: MockDataProvider.publicAmbiguousRegionMessageID))
    #expect(channel.routeType == .tcFlood)
    #expect(channel.regionScope == nil)
    #expect(channel.regionScopeMatches == MockDataProvider.ambiguousRegionNames)
    #expect(channel.pathNodes == Data([0x10, 0x20]))
  }

  @Test
  func `rx log region rows land`() async throws {
    let store = try await seededStore()
    let entries = try await store.fetchRxLogEntries(radioID: radioID)
    let unique = try #require(entries.first { $0.id == MockDataProvider.uniqueRxLogEntryID })
    #expect(unique.regionScope == MockDataProvider.uniqueRegionName)
    #expect(unique.regionScopeMatches == [MockDataProvider.uniqueRegionName])
    #expect(unique.transportCode?.isEmpty == false)

    let ambiguous = try #require(entries.first { $0.id == MockDataProvider.ambiguousRxLogEntryID })
    #expect(ambiguous.regionScope == nil)
    #expect(ambiguous.regionScopeMatches == MockDataProvider.ambiguousRegionNames)
  }

  @Test
  func `node status snapshots seed a GPS track for the location-history node`() async throws {
    let store = try await seededStore()
    let snapshots = try await store.fetchNodeStatusSnapshots(
      nodePublicKey: MockDataProvider.locationHistoryNodePublicKey,
      since: nil
    )
    #expect(snapshots.count == MockDataProvider.nodeStatusSnapshots.count)
    #expect(snapshots.contains { $0.latitude != nil && $0.longitude != nil })
  }

  @Test
  func `reseeding preserves a user-set avatarImageData`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let mode = SimulatorConnectionMode()
    try await mode.seedDataStore(store)

    let jpeg = Data(repeating: 0xCD, count: 16)
    let aliceID = MockDataProvider.aliceChenID
    let existing = try #require(try await store.fetchContact(id: aliceID))
    try await store.saveContact(existing.with(avatarImageData: jpeg))
    #expect(try await store.fetchContact(id: aliceID)?.avatarImageData == jpeg)

    try await mode.seedDataStore(store)

    #expect(try await store.fetchContact(id: aliceID)?.avatarImageData == jpeg)
  }

  @Test
  func `reseeding is idempotent`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let mode = SimulatorConnectionMode()
    try await mode.seedDataStore(store)
    try await mode.seedDataStore(store)

    // Unique-id upsert means a second pass does not duplicate rows.
    let channels = try await store.fetchChannels(radioID: radioID)
    #expect(channels.count == 4)
    let repeats = try await store.fetchMessageRepeats(messageID: MockDataProvider.frankRepeatMessageID)
    #expect(repeats.count == 3)
    let incoming = try await store.fetchMessageRepeats(messageID: MockDataProvider.aliceMultiPathMessageID)
    #expect(incoming.count == MockDataProvider.incomingExtraPathCount)
    let reactions = try await store.fetchReactions(for: MockDataProvider.aliceReactedMessageID)
    #expect(reactions.count == 3)
    let rx = try await store.fetchRxLogEntries(radioID: radioID)
    #expect(rx.count == 2)
  }
}

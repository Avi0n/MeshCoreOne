import Foundation
import Testing
@testable import MC1Services

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
    func linkPreviewColumnsLand() async throws {
        let store = try await seededStore()
        let message = try await store.fetchMessage(id: MockDataProvider.aliceLinkPreviewMessageID)
        let unwrapped = try #require(message)
        #expect(unwrapped.linkPreviewTitle == "Skyline Ridge Trail Guide")
        #expect(unwrapped.linkPreviewFetched == true)
        let imageData = try #require(unwrapped.linkPreviewImageData)
        #expect(!imageData.isEmpty)
    }

    @Test
    func reactionSummaryAndRowsLand() async throws {
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
    func channelNotificationStateLands() async throws {
        let store = try await seededStore()
        let channels = try await store.fetchChannels(radioID: radioID)

        let muted = try #require(channels.first { $0.index == MockDataProvider.trailCrewChannelIndex })
        #expect(muted.notificationLevel == .muted)

        let favorite = try #require(channels.first { $0.index == MockDataProvider.bayAreaChannelIndex })
        #expect(favorite.isFavorite)
    }

    @Test
    func heardRepeatsLand() async throws {
        let store = try await seededStore()
        let repeats = try await store.fetchMessageRepeats(messageID: MockDataProvider.frankRepeatMessageID)
        #expect(repeats.count == 3)
    }

    @Test
    func floodRouteFieldsRoundTripThroughSaveMessage() async throws {
        let store = try await seededStore()
        let floodMessageID = UUID(uuidString: "60000000-0000-0000-0000-000000000004")!
        let message = try #require(try await store.fetchMessage(id: floodMessageID))
        #expect(message.routeType == .tcFlood)
        #expect(message.regionScope == "US915")
    }

    @Test
    func reseedingIsIdempotent() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let mode = SimulatorConnectionMode()
        try await mode.seedDataStore(store)
        try await mode.seedDataStore(store)

        // Unique-id upsert means a second pass does not duplicate rows.
        let channels = try await store.fetchChannels(radioID: radioID)
        #expect(channels.count == 3)
        let repeats = try await store.fetchMessageRepeats(messageID: MockDataProvider.frankRepeatMessageID)
        #expect(repeats.count == 3)
        let reactions = try await store.fetchReactions(for: MockDataProvider.aliceReactedMessageID)
        #expect(reactions.count == 3)
    }
}

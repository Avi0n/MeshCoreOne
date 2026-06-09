import Foundation
import SwiftData
import OSLog

/// Connection mode for simulator and demo mode on device.
/// Provides mock data and simulated connections without requiring real hardware.
@MainActor
public final class SimulatorConnectionMode {

    private let logger = PersistentLogger(subsystem: "com.mc1.services", category: "SimulatorConnectionMode")

    /// Whether simulator is "connected"
    public private(set) var isConnected = false

    /// The simulated device
    public var device: DeviceDTO? {
        isConnected ? MockDataProvider.simulatorDevice : nil
    }

    public init() {}

    /// Simulates connecting to the simulator device
    public func connect() async {
        logger.info("Simulator: connecting to mock device")
        try? await Task.sleep(for: .milliseconds(200))  // Brief delay
        isConnected = true
        logger.info("Simulator: connected")
    }

    /// Simulates disconnecting
    public func disconnect() async {
        logger.info("Simulator: disconnecting")
        isConnected = false
    }

    /// Seeds the data store with mock data. Each message is saved before its
    /// link-preview, reaction, and repeat rows: `saveMessage` does not persist the
    /// link-preview or `reactionSummary` columns, and `saveMessageRepeat` needs the
    /// parent present. Re-seeding upserts on each row's unique `id`, so it is idempotent.
    public func seedDataStore(_ dataStore: PersistenceStore) async throws {
        try await dataStore.saveDevice(MockDataProvider.simulatorDevice)

        for contact in MockDataProvider.contacts {
            try await dataStore.saveContact(contact)
        }

        for channel in MockDataProvider.channels {
            try await dataStore.saveChannel(channel)
        }

        for contact in MockDataProvider.contacts {
            for message in MockDataProvider.messages(for: contact.id) {
                try await dataStore.saveMessage(message)
            }
        }

        for channel in MockDataProvider.channels {
            for message in MockDataProvider.channelMessages(for: channel.index) {
                try await dataStore.saveMessage(message)
            }
        }

        // Link previews render offline from the message-owned columns, which saveMessage drops.
        for seed in MockDataProvider.linkPreviewSeeds {
            try await dataStore.updateMessageLinkPreview(
                id: seed.messageID,
                url: seed.url,
                title: seed.title,
                imageData: seed.imageData,
                iconData: nil,
                fetched: true
            )
        }

        // Reaction rows feed the reactor-detail list; the summary drives the badge.
        for reacted in MockDataProvider.reactedMessages {
            for reaction in MockDataProvider.reactions(for: reacted.messageID) {
                try await dataStore.saveReaction(reaction)
            }
            try await dataStore.updateMessageReactionSummary(
                messageID: reacted.messageID,
                summary: reacted.summary
            )
        }

        for messageID in MockDataProvider.messagesWithRepeats {
            for repeatRow in MockDataProvider.messageRepeats(for: messageID) {
                try await dataStore.saveMessageRepeat(repeatRow)
            }
        }

        logger.info(
            "Simulator: seeded \(MockDataProvider.contacts.count) contacts and " +
            "\(MockDataProvider.channels.count) channels with messages"
        )
    }
}

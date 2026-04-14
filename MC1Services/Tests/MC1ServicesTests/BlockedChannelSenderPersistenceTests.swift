import Foundation
import SwiftData
import Testing
@testable import MC1Services

@Suite("BlockedChannelSender Persistence Tests")
struct BlockedChannelSenderPersistenceTests {

    // MARK: - Test Helpers

    private func createTestStore() async throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }

    private let radioID = UUID()

    // MARK: - Save & Fetch

    @Test("Save and fetch round-trip returns the blocked sender")
    func saveAndFetchRoundTrip() async throws {
        let store = try await createTestStore()
        let dto = BlockedChannelSenderDTO(name: "Spammer", radioID: radioID)

        try await store.saveBlockedChannelSender(dto)
        let fetched = try await store.fetchBlockedChannelSenders(radioID: radioID)

        #expect(fetched.count == 1)
        #expect(fetched.first?.name == "Spammer")
        #expect(fetched.first?.radioID == radioID)
    }

    // MARK: - Upsert

    @Test("Re-saving same name updates dateBlocked instead of creating duplicate")
    func upsertUpdateDateBlocked() async throws {
        let store = try await createTestStore()
        let earlier = Date.distantPast
        let later = Date.now

        let first = BlockedChannelSenderDTO(name: "Troll", radioID: radioID, dateBlocked: earlier)
        try await store.saveBlockedChannelSender(first)

        let second = BlockedChannelSenderDTO(name: "Troll", radioID: radioID, dateBlocked: later)
        try await store.saveBlockedChannelSender(second)

        let fetched = try await store.fetchBlockedChannelSenders(radioID: radioID)
        #expect(fetched.count == 1)
        #expect(fetched.first?.dateBlocked == later)
    }

    // MARK: - Delete

    @Test("Delete removes the blocked sender entry")
    func deleteRemovesEntry() async throws {
        let store = try await createTestStore()
        let dto = BlockedChannelSenderDTO(name: "BadGuy", radioID: radioID)
        try await store.saveBlockedChannelSender(dto)

        try await store.deleteBlockedChannelSender(radioID: radioID, name: "BadGuy")
        let fetched = try await store.fetchBlockedChannelSenders(radioID: radioID)

        #expect(fetched.isEmpty)
    }

    // MARK: - Device Scoping

    @Test("Fetch returns only senders blocked for the specified device")
    func fetchScopesToDeviceID() async throws {
        let store = try await createTestStore()
        let otherDeviceID = UUID()

        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "Alice", radioID: radioID)
        )
        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "Bob", radioID: otherDeviceID)
        )

        let device1Results = try await store.fetchBlockedChannelSenders(radioID: radioID)
        let device2Results = try await store.fetchBlockedChannelSenders(radioID: otherDeviceID)

        #expect(device1Results.count == 1)
        #expect(device1Results.first?.name == "Alice")
        #expect(device2Results.count == 1)
        #expect(device2Results.first?.name == "Bob")
    }

    // MARK: - Case Insensitivity

    @Test("Name preserves original casing for display")
    func namePreservesOriginalCasing() async throws {
        let store = try await createTestStore()
        let dto = BlockedChannelSenderDTO(name: "Alice", radioID: radioID)
        try await store.saveBlockedChannelSender(dto)

        let fetched = try await store.fetchBlockedChannelSenders(radioID: radioID)
        #expect(fetched.first?.name == "Alice")
    }

    @Test("Saving same name with different case creates separate entries")
    func caseSensitiveSave() async throws {
        let store = try await createTestStore()

        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "Alice", radioID: radioID, dateBlocked: .distantPast)
        )
        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "ALICE", radioID: radioID, dateBlocked: .now)
        )

        let fetched = try await store.fetchBlockedChannelSenders(radioID: radioID)
        #expect(fetched.count == 2)
    }

    @Test("Delete requires exact case match")
    func caseSensitiveDelete() async throws {
        let store = try await createTestStore()
        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "Alice", radioID: radioID)
        )

        try await store.deleteBlockedChannelSender(radioID: radioID, name: "ALICE")
        let fetched = try await store.fetchBlockedChannelSenders(radioID: radioID)

        #expect(fetched.count == 1)
        #expect(fetched.first?.name == "Alice")
    }

    // MARK: - Sort Order

    @Test("Fetch returns senders sorted by most recently blocked first")
    func fetchSortedByDateBlockedDescending() async throws {
        let store = try await createTestStore()
        let oldest = Date(timeIntervalSince1970: 1_000_000)
        let middle = Date(timeIntervalSince1970: 2_000_000)
        let newest = Date(timeIntervalSince1970: 3_000_000)

        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "first", radioID: radioID, dateBlocked: oldest)
        )
        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "second", radioID: radioID, dateBlocked: newest)
        )
        try await store.saveBlockedChannelSender(
            BlockedChannelSenderDTO(name: "third", radioID: radioID, dateBlocked: middle)
        )

        let fetched = try await store.fetchBlockedChannelSenders(radioID: radioID)
        #expect(fetched.map(\.name) == ["second", "third", "first"])
    }
}

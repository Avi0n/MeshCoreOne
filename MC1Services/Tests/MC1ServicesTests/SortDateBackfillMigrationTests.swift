import Foundation
import SwiftData
import Testing
@testable import MC1Services

@Suite("Message sortDate backfill migration", .serialized)
struct SortDateBackfillMigrationTests {

    private func createTestStore() async throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }

    @Test("Pre-existing rows get sortDate backfilled to createdAt")
    func backfillSetsSortDateToCreatedAt() async throws {
        let store = try await createTestStore()
        await store.resetSortDateBackfillMigrationFlag()
        let radioID = UUID()

        let firstID = UUID()
        let firstCreatedAt = Date(timeIntervalSince1970: 1_704_067_200)
        try await store.insertMessageWithSortDate(
            id: firstID,
            radioID: radioID,
            text: "Hello",
            createdAt: firstCreatedAt,
            sortDate: .distantPast
        )

        let secondID = UUID()
        let secondCreatedAt = Date(timeIntervalSince1970: 1_704_070_800)
        try await store.insertMessageWithSortDate(
            id: secondID,
            radioID: radioID,
            text: "World",
            createdAt: secondCreatedAt,
            sortDate: .distantPast
        )

        try await store.performSortDateBackfillMigration()

        let messages = try await store.fetchAllMessages()
        #expect(messages.count == 2)
        for message in messages {
            #expect(message.sortDate == message.createdAt)
        }
    }

    @Test("Migration is idempotent — second run is a no-op")
    func migrationIsIdempotent() async throws {
        let store = try await createTestStore()
        await store.resetSortDateBackfillMigrationFlag()
        let radioID = UUID()

        let messageID = UUID()
        let createdAt = Date(timeIntervalSince1970: 1_704_067_200)
        try await store.insertMessageWithSortDate(
            id: messageID,
            radioID: radioID,
            text: "Hello",
            createdAt: createdAt,
            sortDate: .distantPast
        )

        try await store.performSortDateBackfillMigration()

        let backfilled = try await store.fetchMessage(id: messageID)
        #expect(backfilled?.sortDate == createdAt)

        // Re-skew the row after the first run. A second call with the flag set must
        // not touch it — otherwise the backfill would re-run every launch.
        try await store.setMessageSortDate(id: messageID, sortDate: .distantPast)
        try await store.performSortDateBackfillMigration()

        let afterSecondRun = try await store.fetchMessage(id: messageID)
        #expect(afterSecondRun?.sortDate == .distantPast, "second run must be a no-op")
    }
}

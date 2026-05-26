import Foundation
import SwiftData
import Testing
@testable import MC1Services

@Suite("Message sortDate reset migration", .serialized)
struct SortDateResetMigrationTests {

    private func createTestStore() async throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }

    @Test("Reset re-normalizes a send-time sortDate back to createdAt")
    func resetUnburiesSendTimeSortDate() async throws {
        let store = try await createTestStore()
        await store.resetSortDateResetMigrationFlag()
        let radioID = UUID()

        // A row buried by the interim send-time sort: createdAt is the drain time,
        // but sortDate was written far in the past from the sender's clock. The
        // original backfill already ran (flag set), so only this reset can fix it.
        let messageID = UUID()
        let createdAt = Date(timeIntervalSince1970: 1_704_067_200)
        let buriedSortDate = Date(timeIntervalSince1970: 1_700_000_000)
        try await store.insertMessageWithSortDate(
            id: messageID,
            radioID: radioID,
            text: "Buried backlog",
            createdAt: createdAt,
            sortDate: buriedSortDate
        )

        try await store.performSortDateResetMigration()

        let migrated = try await store.fetchMessage(id: messageID)
        #expect(migrated?.sortDate == createdAt)
    }

    @Test("Reset is idempotent — second run is a no-op")
    func resetIsIdempotent() async throws {
        let store = try await createTestStore()
        await store.resetSortDateResetMigrationFlag()
        let radioID = UUID()

        let messageID = UUID()
        let createdAt = Date(timeIntervalSince1970: 1_704_067_200)
        try await store.insertMessageWithSortDate(
            id: messageID,
            radioID: radioID,
            text: "Hello",
            createdAt: createdAt,
            sortDate: Date(timeIntervalSince1970: 1_700_000_000)
        )

        try await store.performSortDateResetMigration()
        #expect(try await store.fetchMessage(id: messageID)?.sortDate == createdAt)

        // Re-skew after the first run; the flag must keep a second run from touching it.
        try await store.setMessageSortDate(id: messageID, sortDate: .distantPast)
        try await store.performSortDateResetMigration()
        #expect(try await store.fetchMessage(id: messageID)?.sortDate == .distantPast, "second run must be a no-op")
    }
}

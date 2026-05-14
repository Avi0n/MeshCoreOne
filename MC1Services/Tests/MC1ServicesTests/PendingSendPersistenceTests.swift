import Testing
import Foundation
import SwiftData
@testable import MC1Services

@MainActor
struct PendingSendPersistenceTests {

    @Test("PendingSendDTO round-trips through Model and back")
    func dtoRoundTrip() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let dto = PendingSendDTO(
            id: UUID(),
            radioID: radioID,
            messageID: UUID(),
            kind: .channel,
            contactID: nil,
            channelIndex: 3,
            isResend: true,
            messageText: "test",
            messageTimestamp: 1_700_000_000,
            localNodeName: "Alice",
            sequence: 1,
            enqueuedAt: Date(timeIntervalSince1970: 1_700_000_000)
        )

        try await store.upsertPendingSend(dto)
        let fetched = try await store.fetchPendingSends(radioID: radioID)

        #expect(fetched.count == 1)
        #expect(fetched.first == dto)
    }

    @Test("Pending sends are scoped by radioID")
    func radioScoping() async throws {
        let store = try makeStore()
        let radioA = UUID()
        let radioB = UUID()
        try await store.upsertPendingSend(.fixture(radioID: radioA, sequence: 1))
        try await store.upsertPendingSend(.fixture(radioID: radioB, sequence: 1))

        let aRows = try await store.fetchPendingSends(radioID: radioA)
        let bRows = try await store.fetchPendingSends(radioID: radioB)

        #expect(aRows.count == 1)
        #expect(bRows.count == 1)
        #expect(aRows.first?.radioID == radioA)
        #expect(bRows.first?.radioID == radioB)
    }

    @Test("fetchPendingSends returns rows in sequence order")
    func sequenceOrdering() async throws {
        let store = try makeStore()
        let radioID = UUID()
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 3))
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 1))
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 2))

        let rows = try await store.fetchPendingSends(radioID: radioID)

        #expect(rows.map(\.sequence) == [1, 2, 3])
    }

    @Test("deletePendingSend removes the row")
    func deletion() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let dto = PendingSendDTO.fixture(radioID: radioID, sequence: 1)
        try await store.upsertPendingSend(dto)
        try await store.deletePendingSend(id: dto.id)

        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.isEmpty)
    }

    @Test("insertPendingSendAssigningSequence returns 1 on an empty table")
    func assignSequenceOnEmptyTable() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let dto = PendingSendDTO.fixture(radioID: radioID, sequence: 0)

        let assigned = try await store.insertPendingSendAssigningSequence(dto)

        #expect(assigned == 1)
        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 1)
        #expect(rows.first?.sequence == 1)
    }

    @Test("insertPendingSendAssigningSequence assigns per-radio monotonic sequences")
    func assignSequenceMonotonicPerRadio() async throws {
        let store = try makeStore()
        let radioA = UUID()
        let radioB = UUID()

        let firstA = try await store.insertPendingSendAssigningSequence(.fixture(radioID: radioA, sequence: 0))
        let secondA = try await store.insertPendingSendAssigningSequence(.fixture(radioID: radioA, sequence: 0))
        let firstB = try await store.insertPendingSendAssigningSequence(.fixture(radioID: radioB, sequence: 0))

        #expect(firstA == 1)
        #expect(secondA == 2)
        #expect(firstB == 1, "Sequence numbering is per-radio")
    }

    @Test("Concurrent inserts on the same radio produce distinct sequences")
    func assignSequenceConcurrentInserts() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let dtoA = PendingSendDTO.fixture(radioID: radioID, sequence: 0)
        let dtoB = PendingSendDTO.fixture(radioID: radioID, sequence: 0)

        async let seqA = store.insertPendingSendAssigningSequence(dtoA)
        async let seqB = store.insertPendingSendAssigningSequence(dtoB)
        let assigned = try await [seqA, seqB].sorted()

        #expect(assigned == [1, 2], "Serial @ModelActor isolation must produce distinct sequences")
        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 2)
        #expect(Set(rows.map(\.sequence)) == [1, 2])
    }

    @Test("deletePendingSendsForMessage removes every row matching the messageID")
    func deletePendingSendsForMessageRemovesMatchingRows() async throws {
        let store = try makeStore()
        let radioA = UUID()
        let radioB = UUID()
        let sharedMessageID = UUID()
        let otherMessageID = UUID()

        try await store.upsertPendingSend(.fixture(radioID: radioA, sequence: 1, messageID: sharedMessageID))
        try await store.upsertPendingSend(.fixture(radioID: radioB, sequence: 1, messageID: sharedMessageID))
        try await store.upsertPendingSend(.fixture(radioID: radioA, sequence: 2, messageID: otherMessageID))

        try await store.deletePendingSendsForMessage(messageID: sharedMessageID)

        let rowsA = try await store.fetchPendingSends(radioID: radioA)
        let rowsB = try await store.fetchPendingSends(radioID: radioB)
        #expect(rowsA.count == 1)
        #expect(rowsA.first?.messageID == otherMessageID)
        #expect(rowsB.isEmpty)
    }

    @Test("deletePendingSendsForMessage is a no-op when no rows match")
    func deletePendingSendsForMessageNoMatch() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let messageID = UUID()
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 1, messageID: messageID))

        try await store.deletePendingSendsForMessage(messageID: UUID())

        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 1)
        #expect(rows.first?.messageID == messageID)
    }

    @Test("fetchPendingSends skips rows whose kindRawValue is unknown")
    func fetchSkipsUnknownKindRows() async throws {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let radioID = UUID()

        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 1))

        let futureCaseRow = PendingSend(
            id: UUID(),
            radioID: radioID,
            messageID: UUID(),
            kindRawValue: 99,
            contactID: nil,
            channelIndex: nil,
            isResend: false,
            messageText: "",
            messageTimestamp: 0,
            localNodeName: nil,
            sequence: 2,
            enqueuedAt: Date()
        )
        container.mainContext.insert(futureCaseRow)
        try container.mainContext.save()

        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 1, "Unknown-kind row must be filtered out")
        #expect(rows.first?.sequence == 1)
    }

    private func makeStore() throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }
}

private extension PendingSendDTO {
    static func fixture(
        radioID: UUID = UUID(),
        sequence: Int,
        messageID: UUID = UUID()
    ) -> PendingSendDTO {
        PendingSendDTO(
            id: UUID(),
            radioID: radioID,
            messageID: messageID,
            kind: .dm,
            contactID: UUID(),
            channelIndex: nil,
            isResend: false,
            messageText: "",
            messageTimestamp: 0,
            localNodeName: nil,
            sequence: sequence,
            enqueuedAt: Date()
        )
    }
}

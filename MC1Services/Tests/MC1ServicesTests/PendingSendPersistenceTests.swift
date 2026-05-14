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

    /// `attemptCount` rides through `insertPendingSendAssigningSequence` →
    /// `fetchPendingSends` (DTO → @Model → DTO) without normalization or
    /// loss. The convenience initializer `PendingSend(dto:)` must forward
    /// the value verbatim — per CLAUDE.md "Backup and restore", a stored
    /// field that distinguishes pre-plan rows (`nil`) from current-build
    /// rows (`0` or positive) must not collapse through a normalizing
    /// accessor at materialization time. Round-tripping each of the three
    /// distinguishable states verifies the column survives intact.
    @Test("attemptCount round-trips through insertPendingSendAssigningSequence and fetchPendingSends")
    func attemptCountRoundTripsThroughInsertAndFetch() async throws {
        let store = try makeStore()
        let radioID = UUID()

        func dto(attemptCount: Int?, messageID: UUID) -> PendingSendDTO {
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
                sequence: 0,
                enqueuedAt: Date(),
                attemptCount: attemptCount
            )
        }

        let legacyID = UUID()
        let raceWindowID = UUID()
        let drainedID = UUID()
        _ = try await store.insertPendingSendAssigningSequence(dto(attemptCount: nil, messageID: legacyID))
        _ = try await store.insertPendingSendAssigningSequence(dto(attemptCount: 0, messageID: raceWindowID))
        _ = try await store.insertPendingSendAssigningSequence(dto(attemptCount: 4, messageID: drainedID))

        let rows = try await store.fetchPendingSends(radioID: radioID)
        let legacyRow = try #require(rows.first(where: { $0.messageID == legacyID }))
        let raceWindowRow = try #require(rows.first(where: { $0.messageID == raceWindowID }))
        let drainedRow = try #require(rows.first(where: { $0.messageID == drainedID }))

        #expect(legacyRow.attemptCount == nil,
                "pre-plan row stored as nil must come back as nil — backfill is the only path that promotes")
        #expect(raceWindowRow.attemptCount == 0,
                "current-build race-window row stored as 0 must come back as 0")
        #expect(drainedRow.attemptCount == 4,
                "positive attemptCount must round-trip without normalization")
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

    /// `replacePendingSendForRetry` happy path: a `.failed` row is flipped
    /// to `.pending`, the prior `PendingSend` for the same `messageID` is
    /// gone, and a fresh `PendingSend` row exists with the supplied DTO
    /// fields and a sequence assigned per-radio.
    @Test("replacePendingSendForRetry flips status and inserts a new PendingSend row")
    func replacePendingSendForRetry_FlipsStatusAndInsertsRow() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let messageID = UUID()
        let contactID = UUID()

        try await store.saveMessage(makeOutgoingDM(id: messageID, radioID: radioID, contactID: contactID, status: .failed))
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 1, messageID: messageID))

        let newDTO = PendingSendDTO(
            envelope: DirectMessageEnvelope(messageID: messageID, contactID: contactID),
            radioID: radioID
        )
        let assigned = try await store.replacePendingSendForRetry(messageID: messageID, dto: newDTO)

        let refreshed = try #require(try await store.fetchMessage(id: messageID))
        #expect(refreshed.status == .pending, "Status must flip to .pending")

        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 1, "Old PendingSend row must be replaced, not duplicated")
        let row = try #require(rows.first)
        #expect(row.id == newDTO.id, "Stored row id must match the new DTO")
        #expect(row.messageID == messageID)
        #expect(row.kind == .dm)
        #expect(row.contactID == contactID)
        #expect(row.sequence == assigned, "Assigned sequence must match the persisted row")
        #expect(assigned == 1, "Replacing the only row for the radio starts fresh at 1")
    }

    /// `.delivered` is the race-won state: a late ACK that landed while
    /// the user was reaching for retry must not be clobbered back to
    /// `.pending`. The `PendingSend` row is still inserted — the queue's
    /// drain-time gate handles the no-op send.
    @Test("replacePendingSendForRetry preserves .delivered status")
    func replacePendingSendForRetry_PreservesDelivered() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let messageID = UUID()
        let contactID = UUID()

        try await store.saveMessage(makeOutgoingDM(id: messageID, radioID: radioID, contactID: contactID, status: .delivered))

        let newDTO = PendingSendDTO(
            envelope: DirectMessageEnvelope(messageID: messageID, contactID: contactID),
            radioID: radioID
        )
        _ = try await store.replacePendingSendForRetry(messageID: messageID, dto: newDTO)

        let refreshed = try #require(try await store.fetchMessage(id: messageID))
        #expect(refreshed.status == .delivered, ".delivered must not be downgraded by retry")

        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 1, "PendingSend row is still inserted; drain gate handles no-op")
    }

    /// All prior PendingSend rows that match the messageID are removed.
    /// Older runs that may have racked up multiple PendingSend rows on
    /// the same messageID across reconnects must not survive the retry.
    @Test("replacePendingSendForRetry removes every existing PendingSend row for the messageID")
    func replacePendingSendForRetry_RemovesExistingPendingSendRow() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let messageID = UUID()
        let contactID = UUID()

        try await store.saveMessage(makeOutgoingDM(id: messageID, radioID: radioID, contactID: contactID, status: .failed))
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 1, messageID: messageID))
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 2, messageID: messageID))
        let unrelatedMessageID = UUID()
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 3, messageID: unrelatedMessageID))

        let newDTO = PendingSendDTO(
            envelope: DirectMessageEnvelope(messageID: messageID, contactID: contactID),
            radioID: radioID
        )
        _ = try await store.replacePendingSendForRetry(messageID: messageID, dto: newDTO)

        let rows = try await store.fetchPendingSends(radioID: radioID)
        #expect(rows.count == 2, "Unrelated row survives; both prior rows for the retried messageID are gone")
        let retried = try #require(rows.first(where: { $0.messageID == messageID }))
        #expect(retried.id == newDTO.id)
        let unrelated = try #require(rows.first(where: { $0.messageID == unrelatedMessageID }))
        #expect(unrelated.sequence == 3, "Unrelated row keeps its prior sequence")
    }

    /// Sequence assignment matches `insertPendingSendAssigningSequence`:
    /// the new row gets `max(sequence) + 1` for the radio. Verifies the
    /// per-radio monotonic invariant survives the delete-then-insert
    /// transaction.
    @Test("replacePendingSendForRetry assigns the next per-radio sequence")
    func replacePendingSendForRetry_AssignsNextSequence() async throws {
        let store = try makeStore()
        let radioID = UUID()
        let otherRadioID = UUID()
        let messageID = UUID()
        let contactID = UUID()

        try await store.saveMessage(makeOutgoingDM(id: messageID, radioID: radioID, contactID: contactID, status: .failed))
        try await store.upsertPendingSend(.fixture(radioID: radioID, sequence: 7, messageID: UUID()))
        try await store.upsertPendingSend(.fixture(radioID: otherRadioID, sequence: 99, messageID: UUID()))

        let newDTO = PendingSendDTO(
            envelope: DirectMessageEnvelope(messageID: messageID, contactID: contactID),
            radioID: radioID
        )
        let assigned = try await store.replacePendingSendForRetry(messageID: messageID, dto: newDTO)

        #expect(assigned == 8, "Sequence advances from the highest live value for this radio")
        let rows = try await store.fetchPendingSends(radioID: radioID)
        let inserted = try #require(rows.first(where: { $0.messageID == messageID }))
        #expect(inserted.sequence == 8)
    }

    private func makeOutgoingDM(
        id: UUID,
        radioID: UUID,
        contactID: UUID,
        status: MessageStatus
    ) -> MessageDTO {
        MessageDTO(
            id: id,
            radioID: radioID,
            contactID: contactID,
            channelIndex: nil,
            text: "retry me",
            timestamp: 1_700_000_000,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            direction: .outgoing,
            status: status,
            textType: .plain,
            ackCode: nil,
            pathLength: 0,
            snr: nil,
            senderKeyPrefix: nil,
            senderNodeName: nil,
            isRead: true,
            replyToID: nil,
            roundTripTime: nil,
            heardRepeats: 0,
            retryAttempt: 0,
            maxRetryAttempts: 0
        )
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

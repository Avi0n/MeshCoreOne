import Foundation
import os
import SwiftData

extension PersistenceStore {

    private static let pendingSendLogger = Logger(
        subsystem: "MC1Services",
        category: "PersistenceStore.PendingSends"
    )

    // MARK: - PendingSend CRUD

    /// Insert (or update if `dto.id` already exists) a pending send row.
    /// The `dto.sequence` value is used as-is. Used by tests that need to
    /// pin sequence values; the production enqueue path uses
    /// `insertPendingSendAssigningSequence(_:)` instead so the sequence
    /// is assigned atomically.
    public func upsertPendingSend(_ dto: PendingSendDTO) throws {
        let id = dto.id
        let predicate = #Predicate<PendingSend> { row in
            row.id == id
        }
        var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
        descriptor.fetchLimit = 1

        if let existing = try modelContext.fetch(descriptor).first {
            existing.radioID = dto.radioID
            existing.messageID = dto.messageID
            existing.kindRawValue = dto.kind.rawValue
            existing.contactID = dto.contactID
            existing.channelIndex = dto.channelIndex
            existing.isResend = dto.isResend
            existing.messageText = dto.messageText
            existing.messageTimestamp = dto.messageTimestamp
            existing.localNodeName = dto.localNodeName
            existing.sequence = dto.sequence
            existing.enqueuedAt = dto.enqueuedAt
        } else {
            modelContext.insert(PendingSend(dto: dto))
        }
        try modelContext.save()
    }

    /// Insert a new pending send row, atomically computing and assigning
    /// the next sequence number for the row's radio. The `dto.sequence`
    /// field is IGNORED — the assigned value is the one that lands on
    /// disk, and it is also the return value.
    ///
    /// Atomicity: the read of `max(sequence)` and the insert both run on
    /// `PersistenceStore`'s serial `@ModelActor` isolation, so two
    /// concurrent enqueues from main never see the same `max+1`. This is
    /// the production enqueue path; `upsertPendingSend(_:)` above is
    /// reserved for tests and explicit-sequence scenarios.
    public func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) throws -> Int {
        let scopedRadioID = dto.radioID
        let radioPredicate = #Predicate<PendingSend> { row in
            row.radioID == scopedRadioID
        }
        var seqDescriptor = FetchDescriptor<PendingSend>(
            predicate: radioPredicate,
            sortBy: [SortDescriptor(\.sequence, order: .reverse)]
        )
        seqDescriptor.fetchLimit = 1
        let latest = try modelContext.fetch(seqDescriptor).first
        let assignedSequence = (latest?.sequence ?? 0) + 1

        let inserted = PendingSend(dto: dto)
        inserted.sequence = assignedSequence
        modelContext.insert(inserted)
        try modelContext.save()
        return assignedSequence
    }

    /// Fetch all pending sends for a given radio, ordered by sequence
    /// ascending. Rows whose `kindRawValue` does not resolve to a known
    /// `PendingSendKind` case are skipped and logged at warning level —
    /// such a row could only exist after a downgrade from a future build
    /// that introduced a new case, and silently routing it to `.dm` would
    /// strand the row on disk via the envelope-materialization nil guard.
    public func fetchPendingSends(radioID: UUID) throws -> [PendingSendDTO] {
        let scopedRadioID = radioID
        let predicate = #Predicate<PendingSend> { row in
            row.radioID == scopedRadioID
        }
        let descriptor = FetchDescriptor<PendingSend>(
            predicate: predicate,
            sortBy: [SortDescriptor(\.sequence, order: .forward)]
        )
        return try modelContext.fetch(descriptor).compactMap { row in
            guard PendingSendKind(rawValue: row.kindRawValue) != nil else {
                Self.pendingSendLogger.warning(
                    "Skipping PendingSend \(row.id, privacy: .public) with unknown kindRawValue=\(row.kindRawValue, privacy: .public)"
                )
                return nil
            }
            return row.toDTO()
        }
    }

    /// Delete a pending send by row id. No-op if the id is not present.
    public func deletePendingSend(id: UUID) throws {
        let target = id
        let predicate = #Predicate<PendingSend> { row in
            row.id == target
        }
        var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
        descriptor.fetchLimit = 1
        if let row = try modelContext.fetch(descriptor).first {
            modelContext.delete(row)
            try modelContext.save()
        }
    }

    /// Delete every pending send row whose `messageID` matches. No-op if no
    /// rows match. Used at drain time when only the envelope's `messageID`
    /// is available; the radio is intentionally NOT part of the predicate
    /// because the user can switch conversations (and therefore the radio
    /// in scope) between enqueue and drain, and the same `messageID` may
    /// be enqueued more than once on the resend path. `messageID` is a
    /// UUID so cross-radio collision is effectively zero.
    public func deletePendingSendsForMessage(messageID: UUID) throws {
        let scopedMessageID = messageID
        let predicate = #Predicate<PendingSend> { row in
            row.messageID == scopedMessageID
        }
        let descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
        let rows = try modelContext.fetch(descriptor)
        guard !rows.isEmpty else { return }
        for row in rows {
            modelContext.delete(row)
        }
        try modelContext.save()
    }
}

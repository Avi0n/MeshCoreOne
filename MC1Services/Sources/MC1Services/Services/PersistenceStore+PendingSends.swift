import Foundation
import os
import SwiftData

extension PersistenceStore {

    private static let pendingSendLogger = Logger(
        subsystem: "com.mc1",
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
            existing.attemptCount = dto.attemptCount
        } else {
            modelContext.insert(PendingSend(dto: dto))
        }
        try modelContext.save()
    }

    /// Insert a new pending send row, atomically computing and assigning
    /// the next sequence number for the row's radio. The `dto.sequence`
    /// field is ignored — the assigned value is the one that lands on
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

    /// Atomically replaces any existing `PendingSend` rows for the given
    /// `messageID`, flips the matching `Message.status` to `.pending`
    /// (unless already `.delivered`), and inserts the new `PendingSendDTO`.
    /// All three operations land in a single `modelContext.save()` so a
    /// crash mid-call cannot leave the row at `.pending` with no
    /// `PendingSend` available for replay. The manual DM retry path uses
    /// this to keep the queue authoritative for retries while the prior
    /// multi-await sequence (delete + status flip + enqueue) had crash
    /// windows that stranded `.pending` rows.
    ///
    /// `.delivered` is intentionally preserved — a late-arriving ACK that
    /// landed while the user was reaching for the retry button must not be
    /// clobbered. The `PendingSend` row is still inserted; the queue's
    /// drain-time `hasPendingSend` gate notices the row but `MessageService`
    /// short-circuits the wire send for an already-delivered message.
    ///
    /// Returns the assigned per-radio sequence number, matching the
    /// `insertPendingSendAssigningSequence(_:)` contract.
    public func replacePendingSendForRetry(
        messageID: UUID,
        dto: PendingSendDTO
    ) async throws -> Int {
        let scopedMessageID = messageID
        let pendingPredicate = #Predicate<PendingSend> { row in
            row.messageID == scopedMessageID
        }
        let existing = try modelContext.fetch(FetchDescriptor<PendingSend>(predicate: pendingPredicate))
        for row in existing {
            modelContext.delete(row)
        }

        let messagePredicate = #Predicate<Message> { message in
            message.id == scopedMessageID
        }
        var messageDescriptor = FetchDescriptor<Message>(predicate: messagePredicate)
        messageDescriptor.fetchLimit = 1
        if let message = try modelContext.fetch(messageDescriptor).first,
           message.status != .delivered {
            message.status = .pending
        }

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

    /// Delete every `PendingSend` row whose `radioID` does not match any
    /// known `Device` row. Returns the number of rows deleted. An orphan row
    /// can never drain (`fetchPendingSends(radioID:)` filters by radio and
    /// the radio is gone), so leaving it on disk only wastes space.
    ///
    /// **Precondition (callers must satisfy):** every paired Device row,
    /// including the one currently being constructed, must already be
    /// persisted before this runs. The production wiring at
    /// `ConnectionManager.buildServicesAndSaveDevice` saves the Device row
    /// before invoking warmUp, so this precondition holds for the in-progress
    /// radio as well as every previously-paired one. The doc-comment exists
    /// to head off future refactors that might invoke warmUp ahead of the
    /// Device save — in that order, an in-flight new-pair Device whose row
    /// hasn't been flushed yet would have its enqueued PendingSends wrongly
    /// classified as orphans.
    @discardableResult
    public func purgeOrphanPendingSends() throws -> Int {
        let knownRadioIDs = try Set(modelContext.fetch(FetchDescriptor<Device>()).map(\.radioID))
        let allRows = try modelContext.fetch(FetchDescriptor<PendingSend>())
        let orphans = allRows.filter { !knownRadioIDs.contains($0.radioID) }
        guard !orphans.isEmpty else { return 0 }
        for row in orphans {
            modelContext.delete(row)
        }
        try modelContext.save()
        Self.pendingSendLogger.info("Purged \(orphans.count, privacy: .public) orphan PendingSend rows")
        return orphans.count
    }

    /// Delete every pending send row whose `messageID` matches. No-op if no
    /// rows match. Used at drain time when only the envelope's `messageID`
    /// is available; the radio is intentionally not part of the predicate
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

    /// Bulk non-saving cascade helper. Caller owns the single transactional
    /// save. Issues one `delete(model:where:)` per chunk so wiping thousands
    /// of messages costs O(chunks) bulk ops rather than O(n) fetches.
    ///
    /// **Cascade atomicity:** the cascade is serial within `@ModelActor`
    /// (`PersistenceStore`'s actor isolation serialises awaits), not
    /// transactional in the SQL sense. A crash mid-cascade leaves a
    /// partially-deleted state on disk; the next `purgeOrphanPendingSends`
    /// + hydrate cycle reconciles. Callers requiring strict atomicity stage
    /// every delete in this function and call `save()` exactly once at the
    /// end (the convention every cascade site in this codebase follows).
    ///
    /// **Chunking:** SwiftData translates `contains` predicates to SQL
    /// `IN (?, ?, …)`. SQLite's default `SQLITE_MAX_VARIABLE_NUMBER` on
    /// iOS 18+ is 32766, so wiping a radio with >32k messages would hit
    /// the ceiling unchunked. 500 keeps headroom and keeps the predicate
    /// compact for SwiftData's compile-time validation.
    ///
    /// **`@Relationship` cascade bypass:** bulk `delete(model:where:)`
    /// skips the SwiftData change-tracking pipeline, so
    /// `@Relationship(deleteRule: .cascade)` declarations do not fire on
    /// these paths. Any future cascade-by-relationship added to `Message`
    /// must be mirrored explicitly in every cascade site that bulk-deletes
    /// messages.
    internal func _deletePendingSendsForMessageIDsWithoutSaving(messageIDs: [UUID]) throws {
        guard !messageIDs.isEmpty else { return }
        let chunkSize = 500
        for start in stride(from: 0, to: messageIDs.count, by: chunkSize) {
            let chunk = Array(messageIDs[start..<min(start + chunkSize, messageIDs.count)])
            let scopedIDs = chunk
            try modelContext.delete(model: PendingSend.self, where: #Predicate { row in
                scopedIDs.contains(row.messageID)
            })
        }
    }

    public func hasPendingSend(messageID: UUID) throws -> Bool {
        let scopedMessageID = messageID
        let predicate = #Predicate<PendingSend> { row in
            row.messageID == scopedMessageID
        }
        var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
        descriptor.fetchLimit = 1
        return try !modelContext.fetch(descriptor).isEmpty
    }

    /// Fetch every `PendingSend` row that matches a given message ID, ordered
    /// by sequence ascending. Returns DTOs for cross-actor safety. Rows whose
    /// `kindRawValue` does not resolve to a known `PendingSendKind` case are
    /// skipped, matching the contract of `fetchPendingSends(radioID:)`.
    ///
    /// Symmetric with `deletePendingSendsForMessage(messageID:)` — both ignore
    /// the radio because the user can switch radios between enqueue and
    /// observation, and a UUID `messageID` makes cross-radio collision
    /// effectively zero.
    public func fetchPendingSendsForMessage(messageID: UUID) throws -> [PendingSendDTO] {
        let scopedMessageID = messageID
        let predicate = #Predicate<PendingSend> { row in
            row.messageID == scopedMessageID
        }
        let descriptor = FetchDescriptor<PendingSend>(
            predicate: predicate,
            sortBy: [SortDescriptor(\.sequence, order: .forward)]
        )
        return try modelContext.fetch(descriptor).compactMap { row in
            guard PendingSendKind(rawValue: row.kindRawValue) != nil else { return nil }
            return row.toDTO()
        }
    }

    /// Deletes any `PendingSend` row where `attemptCount` is `nil`. Such rows
    /// can only exist via lightweight migration from a schema version that
    /// predates `attemptCount`; their drain history is ambiguous, so the user
    /// re-sends if they care.
    ///
    /// Idempotent: an empty fetch after the first purge across the lifetime of
    /// the storage.
    ///
    /// Called from `PersistenceStore.warmUp()` on every connect.
    @discardableResult
    public func purgeLegacyAttemptCountRows() throws -> Int {
        let nilPredicate = #Predicate<PendingSend> { row in
            row.attemptCount == nil
        }
        let rows = try modelContext.fetch(FetchDescriptor<PendingSend>(predicate: nilPredicate))
        guard !rows.isEmpty else { return 0 }
        for row in rows { modelContext.delete(row) }
        try modelContext.save()
        Self.pendingSendLogger.notice(
            "purgeLegacyAttemptCountRows deleted \(rows.count, privacy: .public) row(s) with attemptCount=nil"
        )
        return rows.count
    }

    /// Increments the `attemptCount` for the `PendingSend` row matching
    /// `messageID`. Returns the new count, or `nil` if no row matched.
    /// Throws on SwiftData read/write failure — callers must treat a thrown
    /// error as transient (park + retry) and `nil` as terminal (deleted row).
    ///
    /// `warmUp`'s purge runs before hydrate, so a `nil` row reaching increment
    /// time would indicate the purge step was skipped (programmer error). The
    /// `?? 0` fallback degrades gracefully without pretending the row had
    /// prior drain attempts.
    @discardableResult
    public func incrementPendingSendAttemptCount(messageID: UUID) throws -> Int? {
        #if DEBUG
        try incrementPendingSendAttemptCountFaultInjection?()
        #endif
        let scopedMessageID = messageID
        let predicate = #Predicate<PendingSend> { row in
            row.messageID == scopedMessageID
        }
        var descriptor = FetchDescriptor<PendingSend>(predicate: predicate)
        descriptor.fetchLimit = 1
        guard let row = try modelContext.fetch(descriptor).first else { return nil }
        let nextCount = (row.attemptCount ?? 0) + 1
        row.attemptCount = nextCount
        try modelContext.save()
        return nextCount
    }

    #if DEBUG
    public func setIncrementPendingSendAttemptCountFaultInjection(_ hook: (@Sendable () throws -> Void)?) {
        incrementPendingSendAttemptCountFaultInjection = hook
    }
    #endif
}

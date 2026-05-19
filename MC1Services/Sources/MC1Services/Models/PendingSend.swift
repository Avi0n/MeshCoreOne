import Foundation
import SwiftData

/// Persistent record of an envelope queued for send. Survives process death
/// so `SendQueue` resumes draining after the app restarts.
///
/// Rows are scoped to a single radio via `radioID` so reconnecting to a
/// different radio does not attempt to send envelopes whose messages live
/// in a different radio's data partition.
///
/// `sequence` is a per-radio monotonic counter assigned by
/// `PersistenceStore.insertPendingSendAssigningSequence(_:)` at enqueue
/// time. `fetchPendingSends` orders by `sequence` ascending so drain order
/// across process death matches the original enqueue order.
///
/// Storage type choices align with `Message` (UInt32 timestamp,
/// UInt8? channelIndex) so future readers don't pay an `Int` conversion
/// hop on every read/write.
///
/// PendingSend uses a discriminated single-table design (`kindRawValue`
/// selects DM vs. channel) rather than two parallel @Model classes. No
/// other @Model in MC1 follows this pattern, but the alternative — two
/// tables with two sets of CRUD — adds far more surface than the ≤6
/// optional fields per variant justify. Documenting the choice here so
/// future maintainers don't re-evaluate it from scratch.
@Model
public final class PendingSend {
    /// Compound indexes targeting the two hot fetch paths:
    /// - `(radioID, sequence)` powers `fetchPendingSends(radioID:)`'s
    ///    ordered scan during hydrate-on-configure.
    /// - `messageID` alone powers `deletePendingSendsForMessage(...)`,
    ///    which runs on every successful send and every non-cancellation
    ///    error — i.e., the highest-frequency read path on this table.
    ///    Radio is intentionally not part of this index because the
    ///    drain-time delete must succeed regardless of which radio is
    ///    currently in scope.
    /// The uniqueness on `id` already produces an implicit index for the
    /// `deletePendingSend(id:)` path, so no third compound is needed.
    #Index<PendingSend>([\.radioID, \.sequence], [\.messageID])

    @Attribute(.unique)
    public var id: UUID

    public var radioID: UUID
    public var messageID: UUID

    /// Discriminator: 0 = DM, 1 = channel. See `PendingSendKind`.
    public var kindRawValue: Int

    public var contactID: UUID?
    public var channelIndex: UInt8?
    public var isResend: Bool

    public var messageText: String
    public var messageTimestamp: UInt32
    public var localNodeName: String?

    public var sequence: Int
    public var enqueuedAt: Date

    /// Number of drain attempts the send queue has progressed past the
    /// `hasPendingSend` gate for this row. Bumped at the top of each drain
    /// attempt, before any wire-affecting work. Three distinguishable states:
    ///
    /// - `nil`     — pre-migration row (lightweight-migrated from a build
    ///               that did not have this column). The prior build's queue
    ///               drained these rows without recording attempts; treat as
    ///               "drain history unknown — may have sent on the wire."
    ///               The warmUp backfill promotes these to `1`, and the
    ///               first post-rehydrate drain bumps to `2` so
    ///               `preserveTimestamp = postBumpCount > 1` returns true,
    ///               protecting mesh dedup against a duplicate landing.
    /// - `0`       — row that has been persisted but has not yet progressed
    ///               past the top-of-drain bump (either fresh enqueue in
    ///               flight, or process death between persist and bump).
    ///               The recipient cannot have seen this packet, so the
    ///               next drain stamps a fresh wire timestamp
    ///               (`preserveTimestamp = false`).
    /// - positive  — at least one drain attempt has run. A wire send may
    ///               already have happened, so auto-retries must preserve the
    ///               original wire timestamp via `postBumpCount > 1`.
    ///
    /// PendingSendDTO is `Sendable, Hashable, Identifiable` only — intentionally
    /// not Codable, intentionally excluded from AppBackupEnvelope — so there
    /// is no on-disk wire format to defend against. The only "legacy" data is
    /// live SwiftData rows persisted by a build that predates this field,
    /// which lightweight migration maps to `nil` and the warmUp backfill
    /// promotes to `1`.
    public var attemptCount: Int?

    public init(
        id: UUID,
        radioID: UUID,
        messageID: UUID,
        kindRawValue: Int,
        contactID: UUID?,
        channelIndex: UInt8?,
        isResend: Bool,
        messageText: String,
        messageTimestamp: UInt32,
        localNodeName: String?,
        sequence: Int,
        enqueuedAt: Date,
        attemptCount: Int? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.messageID = messageID
        self.kindRawValue = kindRawValue
        self.contactID = contactID
        self.channelIndex = channelIndex
        self.isResend = isResend
        self.messageText = messageText
        self.messageTimestamp = messageTimestamp
        self.localNodeName = localNodeName
        self.sequence = sequence
        self.enqueuedAt = enqueuedAt
        self.attemptCount = attemptCount
    }

    public var kind: PendingSendKind {
        PendingSendKind(rawValue: kindRawValue) ?? .dm
    }
}

/// Discriminator for which `SendQueue` a row drains into.
///
/// Raw values are pinned explicitly per CLAUDE.md "Backup and restore" —
/// even though `PendingSendDTO` is excluded from `AppBackupEnvelope`, the
/// discriminator IS persisted via `kindRawValue` and a future case rename
/// must not silently change the on-disk format.
public enum PendingSendKind: Int, Sendable {
    case dm = 0
    case channel = 1
}

/// Sendable DTO mirror of `PendingSend`. Raw `@Model` instances never leave
/// `PersistenceStore`.
///
/// Intentionally non-Codable: pending sends are transient queue state and
/// are excluded from `AppBackupEnvelope` by design. Restoring a pending
/// send from backup would replay a message that may have already been
/// delivered on the radio, so the DTO does not flow through backup-
/// envelope encoding paths.
public struct PendingSendDTO: Sendable, Hashable, Identifiable {
    public let id: UUID
    public let radioID: UUID
    public let messageID: UUID
    public let kind: PendingSendKind
    public let contactID: UUID?
    public let channelIndex: UInt8?
    public let isResend: Bool
    public let messageText: String
    public let messageTimestamp: UInt32
    public let localNodeName: String?
    public let sequence: Int
    public let enqueuedAt: Date
    /// See `PendingSend.attemptCount` for the three-state semantics. The DTO
    /// memberwise init defaults this to `0` (current-build sentinel) so
    /// new envelopes enter disk distinguishable from pre-migration rows that
    /// lightweight-migrate to `nil`.
    public let attemptCount: Int?

    public init(
        id: UUID,
        radioID: UUID,
        messageID: UUID,
        kind: PendingSendKind,
        contactID: UUID?,
        channelIndex: UInt8?,
        isResend: Bool,
        messageText: String,
        messageTimestamp: UInt32,
        localNodeName: String?,
        sequence: Int,
        enqueuedAt: Date,
        attemptCount: Int? = 0
    ) {
        self.id = id
        self.radioID = radioID
        self.messageID = messageID
        self.kind = kind
        self.contactID = contactID
        self.channelIndex = channelIndex
        self.isResend = isResend
        self.messageText = messageText
        self.messageTimestamp = messageTimestamp
        self.localNodeName = localNodeName
        self.sequence = sequence
        self.enqueuedAt = enqueuedAt
        self.attemptCount = attemptCount
    }
}

public extension PendingSend {
    convenience init(dto: PendingSendDTO) {
        self.init(
            id: dto.id,
            radioID: dto.radioID,
            messageID: dto.messageID,
            kindRawValue: dto.kind.rawValue,
            contactID: dto.contactID,
            channelIndex: dto.channelIndex,
            isResend: dto.isResend,
            messageText: dto.messageText,
            messageTimestamp: dto.messageTimestamp,
            localNodeName: dto.localNodeName,
            sequence: dto.sequence,
            enqueuedAt: dto.enqueuedAt,
            attemptCount: dto.attemptCount
        )
    }

    func toDTO() -> PendingSendDTO {
        PendingSendDTO(
            id: id,
            radioID: radioID,
            messageID: messageID,
            kind: kind,
            contactID: contactID,
            channelIndex: channelIndex,
            isResend: isResend,
            messageText: messageText,
            messageTimestamp: messageTimestamp,
            localNodeName: localNodeName,
            sequence: sequence,
            enqueuedAt: enqueuedAt,
            attemptCount: attemptCount
        )
    }
}

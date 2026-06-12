import Foundation

/// Store operations for message rows and their pending-send queue entries.
public protocol MessagePersisting: Actor {

    // MARK: - Message Operations

    /// Check if a message with this deduplication key already exists for the given radio.
    ///
    /// Dedup is scoped per-radio because the content-based key is radio-agnostic, and two
    /// companion radios in the same area can receive the same over-the-air packet. Without
    /// the `radioID` filter the second radio's sync would be suppressed, leaving nothing to
    /// display when the user switches devices.
    func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool

    /// Save a new message
    func saveMessage(_ dto: MessageDTO) async throws

    /// Fetch a message by ID
    func fetchMessage(id: UUID) async throws -> MessageDTO?

    /// Fetch messages for a contact
    func fetchMessages(contactID: UUID, limit: Int, offset: Int) async throws -> [MessageDTO]

    /// Fetch messages for a channel
    func fetchMessages(radioID: UUID, channelIndex: UInt8, limit: Int, offset: Int) async throws -> [MessageDTO]

    /// Batch fetch last messages for multiple contacts in a single actor call.
    /// Avoids N actor hops when loading message previews for the conversation list.
    func fetchLastMessages(contactIDs: [UUID], limit: Int) throws -> [UUID: [MessageDTO]]

    /// Batch fetch last messages for multiple channels in a single actor call.
    /// Each tuple contains (radioID, channelIndex, id) where id is used as the dictionary key.
    func fetchLastChannelMessages(channels: [(radioID: UUID, channelIndex: UInt8, id: UUID)], limit: Int) throws -> [UUID: [MessageDTO]]

    /// Finds a channel message matching a parsed reaction within a timestamp window
    func findChannelMessageForReaction(
        radioID: UUID,
        channelIndex: UInt8,
        parsedReaction: ParsedReaction,
        localNodeName: String?,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) async throws -> MessageDTO?

    /// Fetches channel message candidates for meshcore-open reaction matching
    func fetchChannelMessageCandidates(
        radioID: UUID,
        channelIndex: UInt8,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) async throws -> [MessageDTO]

    /// Fetches DM message candidates for meshcore-open reaction matching
    func fetchDMMessageCandidates(
        radioID: UUID,
        contactID: UUID,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) async throws -> [MessageDTO]

    /// Finds a DM message matching a reaction by hash within a timestamp window
    func findDMMessageForReaction(
        radioID: UUID,
        contactID: UUID,
        messageHash: String,
        timestampWindow: ClosedRange<UInt32>,
        limit: Int
    ) async throws -> MessageDTO?

    /// Update message status
    func updateMessageStatus(id: UUID, status: MessageStatus) async throws

    /// Update message status unless delivery has already won the race.
    ///
    /// - Returns: `true` if the row's status was changed, `false` if no row was
    ///   updated (either the row is already `.delivered`, or no row exists for
    ///   the given `id`). Callers must gate failure side effects (e.g., the
    ///   `MessageStatusEvent.failed` broadcast, UI toasts) on the return value
    ///   so they do not surface a `.failed` event for a delivered or absent row.
    func updateMessageStatusUnlessDelivered(id: UUID, status: MessageStatus) async throws -> Bool

    /// Update message ACK info
    func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws

    /// Update message retry status
    func updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws

    /// Update message timestamp (for resending)
    func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws

    /// Update heard repeats count
    func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws

    /// Mark a message as read
    func markMessageAsRead(id: UUID) async throws

    /// Update link preview data for a message
    func updateMessageLinkPreview(
        id: UUID,
        url: String?,
        title: String?,
        imageData: Data?,
        iconData: Data?,
        fetched: Bool
    ) throws

    // MARK: - Pending Sends

    /// Insert (or update if `dto.id` already exists) a pending send row using the sequence value from the DTO.
    func upsertPendingSend(_ dto: PendingSendDTO) async throws

    /// Insert a new pending send row, atomically assigning the next sequence number for the row's radio.
    /// Returns the assigned sequence number.
    func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) async throws -> Int

    /// Fetch all pending sends for a given radio, ordered by sequence ascending.
    func fetchPendingSends(radioID: UUID) async throws -> [PendingSendDTO]

    /// Delete a pending send by row id. No-op if the id is not present.
    func deletePendingSend(id: UUID) async throws

    /// Delete every pending send row whose `messageID` matches. No-op if no rows match.
    /// `messageID` is globally unique across all radios, so scoping by radio would be
    /// redundant and could miss stale rows from prior pairings.
    func deletePendingSendsForMessage(messageID: UUID) async throws

    /// `messageID` is globally unique.
    func hasPendingSend(messageID: UUID) async throws -> Bool

    /// Increments the `attemptCount` for the `PendingSend` row matching
    /// `messageID`. Returns the new count, or `nil` if no row matched.
    /// Throws on SwiftData read/write failure; callers must treat a thrown
    /// error as transient (park + retry) and `nil` as terminal (deleted row).
    @discardableResult
    func incrementPendingSendAttemptCount(messageID: UUID) async throws -> Int?
}

// MARK: - Default Parameter Values

extension MessagePersisting {
    /// Update message ACK info with no round-trip time
    func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus) async throws {
        try await updateMessageAck(id: id, ackCode: ackCode, status: status, roundTripTime: nil)
    }
}

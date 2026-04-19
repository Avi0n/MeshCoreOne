import Foundation
import MeshCore

/// Protocol for PersistenceStore to enable testability of dependent services.
///
/// This protocol abstracts the SwiftData persistence operations used by services,
/// allowing them to be tested with mock implementations.
///
/// ## Usage
///
/// Services can accept this protocol type for dependency injection:
/// ```swift
/// actor MyService {
///     private let dataStore: any PersistenceStoreProtocol
///
///     init(dataStore: any PersistenceStoreProtocol) {
///         self.dataStore = dataStore
///     }
/// }
/// ```
public protocol PersistenceStoreProtocol: Actor {

    // MARK: - Message Operations

    /// Check if a message with this deduplication key already exists
    func isDuplicateMessage(deduplicationKey: String) async throws -> Bool

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

    /// Update message ACK info
    func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws

    /// Update message retry status
    func updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws

    /// Update message timestamp (for resending)
    func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws

    /// Update heard repeats count
    func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws

    /// Update link preview data for a message
    func updateMessageLinkPreview(
        id: UUID,
        url: String?,
        title: String?,
        imageData: Data?,
        iconData: Data?,
        fetched: Bool
    ) throws

    // MARK: - Contact Operations

    /// Fetch all confirmed contacts for a device
    func fetchContacts(radioID: UUID) async throws -> [ContactDTO]

    /// Fetch contacts with recent messages
    func fetchConversations(radioID: UUID) async throws -> [ContactDTO]

    /// Fetch a contact by ID
    func fetchContact(id: UUID) async throws -> ContactDTO?

    /// Fetch a contact by public key
    func fetchContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO?

    /// Fetch a contact by public key prefix
    func fetchContact(radioID: UUID, publicKeyPrefix: Data) async throws -> ContactDTO?

    /// Fetch all contacts with their public keys for crypto operations.
    /// Returns dictionary mapping 1-byte public key prefix to array of full 32-byte public keys.
    /// Multiple contacts may share the same prefix byte, so we store all of them.
    func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]]

    /// Save or update a contact from a ContactFrame
    @discardableResult
    func saveContact(radioID: UUID, from frame: ContactFrame) async throws -> UUID

    /// Save or update a contact from DTO
    func saveContact(_ dto: ContactDTO) async throws

    /// Delete a contact
    func deleteContact(id: UUID) async throws

    /// Update contact's last message info (nil clears the date, removing from conversations list)
    func updateContactLastMessage(contactID: UUID, date: Date?) async throws

    /// Increment unread count for a contact
    func incrementUnreadCount(contactID: UUID) async throws

    /// Clear unread count for a contact
    func clearUnreadCount(contactID: UUID) async throws

    // MARK: - Mention Tracking

    /// Mark a mention as seen
    func markMentionSeen(messageID: UUID) async throws

    /// Increment unread mention count for a contact
    func incrementUnreadMentionCount(contactID: UUID) async throws

    /// Decrement unread mention count for a contact
    func decrementUnreadMentionCount(contactID: UUID) async throws

    /// Clear unread mention count for a contact
    func clearUnreadMentionCount(contactID: UUID) async throws

    /// Increment unread mention count for a channel
    func incrementChannelUnreadMentionCount(channelID: UUID) async throws

    /// Decrement unread mention count for a channel
    func decrementChannelUnreadMentionCount(channelID: UUID) async throws

    /// Clear unread mention count for a channel
    func clearChannelUnreadMentionCount(channelID: UUID) async throws

    /// Fetch unseen mention message IDs for a contact, ordered oldest-first
    func fetchUnseenMentionIDs(contactID: UUID) async throws -> [UUID]

    /// Fetch unseen mention message IDs for a channel, ordered oldest-first
    func fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UInt8) async throws -> [UUID]

    /// Delete all messages for a contact
    func deleteMessagesForContact(contactID: UUID) async throws

    /// Delete all channel messages from a specific sender for a device
    func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws

    /// Fetch blocked contacts for a device
    func fetchBlockedContacts(radioID: UUID) async throws -> [ContactDTO]

    // MARK: - Blocked Channel Senders

    /// Save a blocked channel sender name (upserts to prevent duplicates)
    func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws

    /// Delete a blocked channel sender by device and name
    func deleteBlockedChannelSender(radioID: UUID, name: String) async throws

    /// Fetch all blocked channel senders for a device
    func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO]

    // MARK: - Channel Operations

    /// Fetch all channels for a device
    func fetchChannels(radioID: UUID) async throws -> [ChannelDTO]

    /// Fetch a channel by index
    func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO?

    /// Fetch a channel by ID
    func fetchChannel(id: UUID) async throws -> ChannelDTO?

    /// Save or update a channel from ChannelInfo
    @discardableResult
    func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID

    /// Save or update a channel from DTO
    func saveChannel(_ dto: ChannelDTO) async throws

    /// Delete a channel
    func deleteChannel(id: UUID) async throws

    /// Delete all messages for a channel
    func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws

    /// Update channel's last message info (nil clears the date)
    func updateChannelLastMessage(channelID: UUID, date: Date?) async throws

    /// Increment unread count for a channel
    func incrementChannelUnreadCount(channelID: UUID) async throws

    /// Clear unread count for a channel
    func clearChannelUnreadCount(channelID: UUID) async throws

    /// Sets the notification level for a channel
    func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws

    /// Sets the notification level for a remote node session
    func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws

    // MARK: - Saved Trace Paths

    /// Fetch all saved trace paths for a device
    func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO]

    /// Fetch a single saved trace path by ID
    func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO?

    /// Create a new saved trace path
    func createSavedTracePath(radioID: UUID, name: String, pathBytes: Data, hashSize: Int, initialRun: TracePathRunDTO?) async throws -> SavedTracePathDTO

    /// Update a saved trace path's name
    func updateSavedTracePathName(id: UUID, name: String) async throws

    /// Delete a saved trace path
    func deleteSavedTracePath(id: UUID) async throws

    /// Append a run to a saved trace path
    func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws

    // MARK: - Heard Repeats

    /// Find a sent channel message matching criteria within a time window
    func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String, withinSeconds: Int) async throws -> MessageDTO?

    /// Save a message repeat entry
    func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws

    /// Fetch all repeats for a message
    func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO]

    /// Delete all repeats for a message
    func deleteMessageRepeats(messageID: UUID) async throws

    /// Check if a repeat exists for the given RX log entry
    func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool

    /// Increment heard repeats count and return new count
    func incrementMessageHeardRepeats(id: UUID) async throws -> Int

    /// Increment send count and return new count
    func incrementMessageSendCount(id: UUID) async throws -> Int

    // MARK: - Debug Log Entries

    /// Save a batch of debug log entries
    func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws

    /// Fetch debug log entries since a given date
    func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO]

    /// Count all debug log entries
    func countDebugLogEntries() async throws -> Int

    /// Prune debug log entries, keeping only the most recent
    func pruneDebugLogEntries(keepCount: Int) async throws

    /// Clear all debug log entries
    func clearDebugLogEntries() async throws

    // MARK: - Link Preview Data

    /// Fetch link preview data by URL
    func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO?

    /// Save or update link preview data
    func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws

    // MARK: - RxLogEntry Lookup

    /// Find RxLogEntry matching an incoming message for path correlation.
    ///
    /// For channel messages: Correlates by channel index and sender timestamp.
    /// For direct messages: Correlates by sender timestamp and payload type.
    func findRxLogEntry(
        channelIndex: UInt8?,
        senderTimestamp: UInt32
    ) async throws -> RxLogEntryDTO?

    /// Find a DM RxLogEntry by matching the sender prefix byte in the packet payload.
    /// Fallback for when the primary timestamp-based lookup fails.
    func findRxLogEntryBySenderPrefix(
        senderPrefixByte: UInt8,
        receivedSince: Date
    ) async throws -> RxLogEntryDTO?

    // MARK: - Room Session State

    /// Mark a session as disconnected without changing permission level.
    /// Use for transient disconnections (BLE drop, keep-alive failure, re-auth failure).
    func markSessionDisconnected(_ sessionID: UUID) async throws

    /// Mark a room session as connected. Returns true if the state actually changed.
    @discardableResult
    func markRoomSessionConnected(_ sessionID: UUID) async throws -> Bool

    /// Update room activity timestamps (sort date and optional sync bookmark).
    func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) async throws

    // MARK: - Room Message Operations

    /// Save a new room message
    func saveRoomMessage(_ dto: RoomMessageDTO) async throws

    /// Fetch a room message by ID
    func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO?

    /// Fetch room messages for a session
    func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO]

    /// Check for duplicate room message
    func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool

    /// Update room message status after send attempt
    func updateRoomMessageStatus(
        id: UUID,
        status: MessageStatus,
        ackCode: UInt32?,
        roundTripTime: UInt32?
    ) async throws

    /// Update room message retry status
    func updateRoomMessageRetryStatus(
        id: UUID,
        status: MessageStatus,
        retryAttempt: Int,
        maxRetryAttempts: Int
    ) async throws

    // MARK: - Discovered Nodes

    /// Insert or update a discovered node from an advertisement frame.
    /// Updates lastHeard timestamp if node already exists.
    /// - Returns: Tuple of (DiscoveredNodeDTO, isNew) where isNew is true only if node was newly created
    func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) async throws -> (node: DiscoveredNodeDTO, isNew: Bool)

    /// Fetch all discovered nodes for a device.
    func fetchDiscoveredNodes(radioID: UUID) async throws -> [DiscoveredNodeDTO]

    /// Delete a discovered node by ID.
    func deleteDiscoveredNode(id: UUID) async throws

    /// Clear all discovered nodes for a device.
    func clearDiscoveredNodes(radioID: UUID) async throws

    /// Batch fetch all contact public keys for efficient "added" state lookup.
    /// Returns public keys of confirmed (non-discovered) contacts only.
    func fetchContactPublicKeys(radioID: UUID) async throws -> Set<Data>

    // MARK: - Reactions

    /// Fetch reactions for a message, ordered by most recent first
    func fetchReactions(for messageID: UUID, limit: Int) async throws -> [ReactionDTO]

    /// Save a new reaction
    func saveReaction(_ dto: ReactionDTO) async throws

    /// Check if a reaction already exists (deduplication)
    func reactionExists(messageID: UUID, senderName: String, emoji: String) async throws -> Bool

    /// Update a message's reaction summary cache
    func updateMessageReactionSummary(messageID: UUID, summary: String?) async throws

    /// Delete all reactions for a message
    func deleteReactionsForMessage(messageID: UUID) async throws

    // MARK: - Node Status Snapshots

    /// Save a node status snapshot from primitive parameters. Returns the snapshot ID.
    func saveNodeStatusSnapshot(
        nodePublicKey: Data,
        batteryMillivolts: UInt16?,
        lastSNR: Double?,
        lastRSSI: Int16?,
        noiseFloor: Int16?,
        uptimeSeconds: UInt32?,
        rxAirtimeSeconds: UInt32?,
        packetsSent: UInt32?,
        packetsReceived: UInt32?,
        receiveErrors: UInt32?,
        postedCount: UInt16?,
        postPushCount: UInt16?
    ) async throws -> UUID

    /// Fetch the most recent snapshot for a node
    func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO?

    /// Fetch snapshots for a node within a date range, sorted by timestamp ascending
    func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO]

    /// Fetch the most recent snapshot before the given date for a node
    func fetchPreviousNodeStatusSnapshot(nodePublicKey: Data, before: Date) async throws -> NodeStatusSnapshotDTO?

    /// Update neighbor data on an existing snapshot
    func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws

    /// Update telemetry data on an existing snapshot
    func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws

    /// Save a telemetry-only snapshot (no radio metrics). Returns the snapshot ID.
    func saveTelemetryOnlySnapshot(
        nodePublicKey: Data,
        telemetryEntries: [TelemetrySnapshotEntry]
    ) async throws -> UUID

    /// Delete snapshots older than the given date
    func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws
}

// MARK: - Default Parameter Values

public extension PersistenceStoreProtocol {
    /// Fetch reactions with default limit of 100
    func fetchReactions(for messageID: UUID) async throws -> [ReactionDTO] {
        try await fetchReactions(for: messageID, limit: 100)
    }

    /// Update room activity with nil sync timestamp (sort date only)
    func updateRoomActivity(_ sessionID: UUID) async throws {
        try await updateRoomActivity(sessionID, syncTimestamp: nil)
    }
}

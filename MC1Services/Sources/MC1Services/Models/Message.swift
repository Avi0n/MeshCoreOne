import Foundation
import SwiftData

/// Message delivery status
public enum MessageStatus: Int, Sendable, Codable {
    case pending = 0
    case sending = 1
    case sent = 2
    case delivered = 3
    case failed = 4
    case retrying = 5
}

/// Message direction
public enum MessageDirection: Int, Sendable, Codable {
    case incoming = 0
    case outgoing = 1
}

/// Represents a message in a conversation.
/// Messages are stored per-device and associated with a contact or channel.
@Model
public final class Message {
    #Index<Message>(
        [\.radioID, \.channelIndex, \.createdAt],
        [\.radioID, \.channelIndex, \.sortDate],
        [\.radioID, \.channelIndex, \.timestamp],
        [\.contactID, \.createdAt],
        [\.contactID, \.sortDate],
        [\.contactID, \.containsSelfMention, \.mentionSeen],
        [\.radioID, \.channelIndex, \.containsSelfMention, \.mentionSeen],
        [\.deduplicationKey]
    )

    /// Unique message identifier
    @Attribute(.unique)
    public var id: UUID

    /// The device this message belongs to
    @Attribute(originalName: "deviceID")
    public var radioID: UUID

    /// Contact ID for direct messages (nil for channel messages)
    public var contactID: UUID?

    /// Channel index for channel messages (nil for direct messages)
    public var channelIndex: UInt8?

    /// Message text content
    public var text: String

    /// Message timestamp (device time)
    public var timestamp: UInt32

    /// Local creation date
    public var createdAt: Date

    /// Date used for send-time ordering of synced backlog messages.
    /// Defaults to `createdAt` for newly created rows.
    public var sortDate: Date = Date.distantPast

    /// Direction (incoming/outgoing)
    public var directionRawValue: Int

    /// Delivery status
    public var statusRawValue: Int

    /// Text type (plain, signed, etc.)
    public var textTypeRawValue: UInt8

    /// ACK code for tracking delivery (outgoing only)
    public var ackCode: UInt32?

    /// Path length when received
    public var pathLength: UInt8

    /// Signal-to-noise ratio in dB
    public var snr: Double?

    /// Path nodes for incoming messages (1 byte per hop, from RxLogEntry correlation)
    public var pathNodes: Data?

    /// Sender public key prefix (6 bytes, for incoming messages)
    public var senderKeyPrefix: Data?

    /// Sender node name (for channel messages, parsed from "NodeName: MessageText" format)
    public var senderNodeName: String?

    /// Whether this message has been read locally
    public var isRead: Bool

    /// Reply-to message ID (for threaded replies)
    public var replyToID: UUID?

    /// Round-trip time in ms (when ACK received)
    public var roundTripTime: UInt32?

    /// Count of mesh repeats heard for this message (outgoing only)
    public var heardRepeats: Int = 0

    /// Number of times this message has been sent (1 = original, 2+ = sent again)
    public var sendCount: Int = 1

    /// Current retry attempt (0 = first attempt, 1 = first retry, etc.)
    public var retryAttempt: Int = 0

    /// Maximum retry attempts configured for this message
    public var maxRetryAttempts: Int = 0

    /// Deduplication key for preventing duplicate incoming messages
    public var deduplicationKey: String?

    /// Link preview URL that was detected (nil if no URL in message)
    public var linkPreviewURL: String?

    /// Title from link metadata
    public var linkPreviewTitle: String?

    /// Preview image data (hero image)
    @Attribute(.externalStorage)
    public var linkPreviewImageData: Data?

    /// Icon/favicon data
    @Attribute(.externalStorage)
    public var linkPreviewIconData: Data?

    /// Whether fetch has been attempted (true = done, false = not yet tried)
    public var linkPreviewFetched: Bool = false

    /// Whether this incoming message contains a mention of the current user
    public var containsSelfMention: Bool = false

    /// Whether the user has scrolled to see this mention (for tracking unread mentions)
    public var mentionSeen: Bool = false

    /// Whether the timestamp was corrected due to sender clock being invalid
    public var timestampCorrected: Bool = false

    /// Original sender timestamp from the wire (for incoming messages when corrected).
    /// Used for reaction hash computation to ensure sender and receiver match.
    /// Nil when timestamp was not corrected or for outgoing messages.
    public var senderTimestamp: UInt32?

    /// Cached reaction summary for scroll performance
    /// Format: "👍:3,❤️:2,😂:1" (emoji:count pairs, ordered by count desc)
    public var reactionSummary: String?

    /// Route type from RxLog correlation (-1 = unknown/uncorrelated)
    public var routeTypeRawValue: Int = -1

    /// Resolved flood region the sender transmitted under, derived from
    /// `transport_codes[0]` at receive time via the RxLog correlation. Incoming
    /// messages only; nil when the sender's region was not in the local
    /// known-regions list at receive time (back-filled by `updateKnownRegions`).
    public var regionScope: String?

    /// Heard repeats for this message (cascade delete)
    @Relationship(deleteRule: .cascade, inverse: \MessageRepeat.message)
    var repeats: [MessageRepeat]?

    public init(
        id: UUID = UUID(),
        radioID: UUID,
        contactID: UUID? = nil,
        channelIndex: UInt8? = nil,
        text: String,
        timestamp: UInt32 = 0,
        createdAt: Date = Date(),
        sortDate: Date? = nil,
        directionRawValue: Int = MessageDirection.outgoing.rawValue,
        statusRawValue: Int = MessageStatus.pending.rawValue,
        textTypeRawValue: UInt8 = TextType.plain.rawValue,
        ackCode: UInt32? = nil,
        pathLength: UInt8 = 0,
        snr: Double? = nil,
        pathNodes: Data? = nil,
        senderKeyPrefix: Data? = nil,
        senderNodeName: String? = nil,
        isRead: Bool = false,
        replyToID: UUID? = nil,
        roundTripTime: UInt32? = nil,
        heardRepeats: Int = 0,
        sendCount: Int = 1,
        retryAttempt: Int = 0,
        maxRetryAttempts: Int = 0,
        deduplicationKey: String? = nil,
        linkPreviewURL: String? = nil,
        linkPreviewTitle: String? = nil,
        linkPreviewImageData: Data? = nil,
        linkPreviewIconData: Data? = nil,
        linkPreviewFetched: Bool = false,
        containsSelfMention: Bool = false,
        mentionSeen: Bool = false,
        timestampCorrected: Bool = false,
        senderTimestamp: UInt32? = nil,
        reactionSummary: String? = nil,
        routeTypeRawValue: Int = -1,
        regionScope: String? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.contactID = contactID
        self.channelIndex = channelIndex
        self.text = text
        self.timestamp = timestamp > 0 ? timestamp : UInt32(createdAt.timeIntervalSince1970)
        self.createdAt = createdAt
        self.sortDate = sortDate ?? createdAt
        self.directionRawValue = directionRawValue
        self.statusRawValue = statusRawValue
        self.textTypeRawValue = textTypeRawValue
        self.ackCode = ackCode
        self.pathLength = pathLength
        self.snr = snr
        self.pathNodes = pathNodes
        self.senderKeyPrefix = senderKeyPrefix
        self.senderNodeName = senderNodeName
        self.isRead = isRead
        self.replyToID = replyToID
        self.roundTripTime = roundTripTime
        self.heardRepeats = heardRepeats
        self.sendCount = sendCount
        self.retryAttempt = retryAttempt
        self.maxRetryAttempts = maxRetryAttempts
        self.deduplicationKey = deduplicationKey
        self.linkPreviewURL = linkPreviewURL
        self.linkPreviewTitle = linkPreviewTitle
        self.linkPreviewImageData = linkPreviewImageData
        self.linkPreviewIconData = linkPreviewIconData
        self.linkPreviewFetched = linkPreviewFetched
        self.containsSelfMention = containsSelfMention
        self.mentionSeen = mentionSeen
        self.timestampCorrected = timestampCorrected
        self.senderTimestamp = senderTimestamp
        self.reactionSummary = reactionSummary
        self.routeTypeRawValue = routeTypeRawValue
        self.regionScope = regionScope
    }

    /// Builds a model instance directly from a DTO. Shared by backup batch-insert
    /// paths so schema changes don't require touching a 30-argument call site.
    public convenience init(dto: MessageDTO) {
        self.init(
            id: dto.id,
            radioID: dto.radioID,
            contactID: dto.contactID,
            channelIndex: dto.channelIndex,
            text: dto.text,
            timestamp: dto.timestamp,
            createdAt: dto.createdAt,
            sortDate: dto.sortDate,
            directionRawValue: dto.direction.rawValue,
            statusRawValue: dto.status.rawValue,
            textTypeRawValue: dto.textType.rawValue,
            ackCode: dto.ackCode,
            pathLength: dto.pathLength,
            snr: dto.snr,
            pathNodes: dto.pathNodes,
            senderKeyPrefix: dto.senderKeyPrefix,
            senderNodeName: dto.senderNodeName,
            isRead: dto.isRead,
            replyToID: dto.replyToID,
            roundTripTime: dto.roundTripTime,
            heardRepeats: dto.heardRepeats,
            sendCount: dto.sendCount,
            retryAttempt: dto.retryAttempt,
            maxRetryAttempts: dto.maxRetryAttempts,
            deduplicationKey: dto.deduplicationKey,
            linkPreviewURL: dto.linkPreviewURL,
            linkPreviewTitle: dto.linkPreviewTitle,
            // External-storage blobs stay nil and fetched stays false so the new
            // row re-fetches its preview; updateMessageLinkPreview writes them.
            linkPreviewImageData: nil,
            linkPreviewIconData: nil,
            linkPreviewFetched: false,
            containsSelfMention: dto.containsSelfMention,
            mentionSeen: dto.mentionSeen,
            timestampCorrected: dto.timestampCorrected,
            senderTimestamp: dto.senderTimestamp,
            reactionSummary: dto.reactionSummary,
            routeTypeRawValue: dto.routeType.map { Int($0.rawValue) } ?? -1,
            regionScope: dto.regionScope
        )
    }
}

// MARK: - Computed Properties

public extension Message {
    /// Direction enum
    var direction: MessageDirection {
        MessageDirection(rawValue: directionRawValue) ?? .outgoing
    }

    /// Status enum
    var status: MessageStatus {
        get { MessageStatus(rawValue: statusRawValue) ?? .pending }
        set { statusRawValue = newValue.rawValue }
    }

    /// Text type enum
    var textType: TextType {
        TextType(rawValue: textTypeRawValue) ?? .plain
    }

    /// Whether this is an outgoing message
    var isOutgoing: Bool {
        direction == .outgoing
    }

    /// Whether this is a channel message
    var isChannelMessage: Bool {
        channelIndex != nil
    }

    /// Whether the message is still pending delivery
    var isPending: Bool {
        status == .pending || status == .sending
    }

    /// Whether the message failed to send
    var hasFailed: Bool {
        status == .failed
    }

}

// MARK: - Sendable DTO

/// A sendable snapshot of Message for cross-actor transfers
public struct MessageDTO: Sendable, Equatable, Hashable, Identifiable, Codable {
    public var id: UUID
    public var radioID: UUID
    public var contactID: UUID?
    public var channelIndex: UInt8?
    public var text: String
    public var timestamp: UInt32
    public var createdAt: Date
    public var sortDate: Date
    public var direction: MessageDirection
    public var status: MessageStatus
    public var textType: TextType
    public var ackCode: UInt32?
    public var pathLength: UInt8
    public var snr: Double?
    public var pathNodes: Data?
    public var senderKeyPrefix: Data?
    public var senderNodeName: String?
    public var isRead: Bool
    public var replyToID: UUID?
    public var roundTripTime: UInt32?
    public var heardRepeats: Int
    public var sendCount: Int
    public var retryAttempt: Int
    public var maxRetryAttempts: Int
    public var deduplicationKey: String?
    public var linkPreviewURL: String?
    public var linkPreviewTitle: String?
    public var linkPreviewImageData: Data?
    public var linkPreviewIconData: Data?
    public var linkPreviewFetched: Bool
    public var containsSelfMention: Bool
    public var mentionSeen: Bool
    public var timestampCorrected: Bool
    public var senderTimestamp: UInt32?
    public var reactionSummary: String?
    public var routeType: RouteType?
    public var regionScope: String?

    // Explicit Codable so backups predating ``sortDate`` decode cleanly.
    // Legacy envelopes have no `sortDate` key; it falls back to `createdAt`,
    // which must be decoded first. Every other field decodes exactly as the
    // synthesized Codable did, preserving the existing wire format.
    private enum CodingKeys: String, CodingKey {
        case id, radioID, contactID, channelIndex, text, timestamp, createdAt,
             sortDate, direction, status, textType, ackCode, pathLength, snr,
             pathNodes, senderKeyPrefix, senderNodeName, isRead, replyToID,
             roundTripTime, heardRepeats, sendCount, retryAttempt, maxRetryAttempts,
             deduplicationKey, linkPreviewURL, linkPreviewTitle, linkPreviewImageData,
             linkPreviewIconData, linkPreviewFetched, containsSelfMention, mentionSeen,
             timestampCorrected, senderTimestamp, reactionSummary, routeType, regionScope
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try container.decode(UUID.self, forKey: .id)
        self.radioID = try container.decode(UUID.self, forKey: .radioID)
        self.contactID = try container.decodeIfPresent(UUID.self, forKey: .contactID)
        self.channelIndex = try container.decodeIfPresent(UInt8.self, forKey: .channelIndex)
        self.text = try container.decode(String.self, forKey: .text)
        self.timestamp = try container.decode(UInt32.self, forKey: .timestamp)
        let createdAt = try container.decode(Date.self, forKey: .createdAt)
        self.createdAt = createdAt
        self.sortDate = try container.decodeIfPresent(Date.self, forKey: .sortDate) ?? createdAt
        self.direction = try container.decode(MessageDirection.self, forKey: .direction)
        self.status = try container.decode(MessageStatus.self, forKey: .status)
        self.textType = try container.decode(TextType.self, forKey: .textType)
        self.ackCode = try container.decodeIfPresent(UInt32.self, forKey: .ackCode)
        self.pathLength = try container.decode(UInt8.self, forKey: .pathLength)
        self.snr = try container.decodeIfPresent(Double.self, forKey: .snr)
        self.pathNodes = try container.decodeIfPresent(Data.self, forKey: .pathNodes)
        self.senderKeyPrefix = try container.decodeIfPresent(Data.self, forKey: .senderKeyPrefix)
        self.senderNodeName = try container.decodeIfPresent(String.self, forKey: .senderNodeName)
        self.isRead = try container.decode(Bool.self, forKey: .isRead)
        self.replyToID = try container.decodeIfPresent(UUID.self, forKey: .replyToID)
        self.roundTripTime = try container.decodeIfPresent(UInt32.self, forKey: .roundTripTime)
        self.heardRepeats = try container.decode(Int.self, forKey: .heardRepeats)
        self.sendCount = try container.decode(Int.self, forKey: .sendCount)
        self.retryAttempt = try container.decode(Int.self, forKey: .retryAttempt)
        self.maxRetryAttempts = try container.decode(Int.self, forKey: .maxRetryAttempts)
        self.deduplicationKey = try container.decodeIfPresent(String.self, forKey: .deduplicationKey)
        self.linkPreviewURL = try container.decodeIfPresent(String.self, forKey: .linkPreviewURL)
        self.linkPreviewTitle = try container.decodeIfPresent(String.self, forKey: .linkPreviewTitle)
        self.linkPreviewImageData = try container.decodeIfPresent(Data.self, forKey: .linkPreviewImageData)
        self.linkPreviewIconData = try container.decodeIfPresent(Data.self, forKey: .linkPreviewIconData)
        self.linkPreviewFetched = try container.decode(Bool.self, forKey: .linkPreviewFetched)
        self.containsSelfMention = try container.decode(Bool.self, forKey: .containsSelfMention)
        self.mentionSeen = try container.decode(Bool.self, forKey: .mentionSeen)
        self.timestampCorrected = try container.decode(Bool.self, forKey: .timestampCorrected)
        self.senderTimestamp = try container.decodeIfPresent(UInt32.self, forKey: .senderTimestamp)
        self.reactionSummary = try container.decodeIfPresent(String.self, forKey: .reactionSummary)
        self.routeType = try container.decodeIfPresent(RouteType.self, forKey: .routeType)
        self.regionScope = try container.decodeIfPresent(String.self, forKey: .regionScope)
    }

    public init(from message: Message, includeLinkPreviewBlobs: Bool = true) {
        self.id = message.id
        self.radioID = message.radioID
        self.contactID = message.contactID
        self.channelIndex = message.channelIndex
        self.text = message.text
        self.timestamp = message.timestamp
        self.createdAt = message.createdAt
        self.sortDate = message.sortDate
        self.direction = message.direction
        self.status = message.status
        self.textType = message.textType
        self.ackCode = message.ackCode
        self.pathLength = message.pathLength
        self.snr = message.snr
        self.pathNodes = message.pathNodes
        self.senderKeyPrefix = message.senderKeyPrefix
        self.senderNodeName = message.senderNodeName
        self.isRead = message.isRead
        self.replyToID = message.replyToID
        self.roundTripTime = message.roundTripTime
        self.heardRepeats = message.heardRepeats
        self.sendCount = message.sendCount
        self.retryAttempt = message.retryAttempt
        self.maxRetryAttempts = message.maxRetryAttempts
        self.deduplicationKey = message.deduplicationKey
        self.linkPreviewURL = message.linkPreviewURL
        self.linkPreviewTitle = message.linkPreviewTitle
        // External-storage blobs (linkPreviewImageData/IconData) are only faulted when
        // accessed. Backup export skips them to avoid loading every preview image into
        // memory just to discard it.
        if includeLinkPreviewBlobs {
            self.linkPreviewImageData = message.linkPreviewImageData
            self.linkPreviewIconData = message.linkPreviewIconData
            self.linkPreviewFetched = message.linkPreviewFetched
        } else {
            self.linkPreviewImageData = nil
            self.linkPreviewIconData = nil
            self.linkPreviewFetched = false
        }
        self.containsSelfMention = message.containsSelfMention
        self.mentionSeen = message.mentionSeen
        self.timestampCorrected = message.timestampCorrected
        self.senderTimestamp = message.senderTimestamp
        self.reactionSummary = message.reactionSummary
        self.routeType = UInt8(exactly: message.routeTypeRawValue)
            .flatMap(RouteType.init(rawValue:))
        self.regionScope = message.regionScope
    }

    /// Memberwise initializer for creating DTOs directly
    public init(
        id: UUID,
        radioID: UUID,
        contactID: UUID?,
        channelIndex: UInt8?,
        text: String,
        timestamp: UInt32,
        createdAt: Date,
        sortDate: Date? = nil,
        direction: MessageDirection,
        status: MessageStatus,
        textType: TextType,
        ackCode: UInt32?,
        pathLength: UInt8,
        snr: Double?,
        pathNodes: Data? = nil,
        senderKeyPrefix: Data?,
        senderNodeName: String?,
        isRead: Bool,
        replyToID: UUID?,
        roundTripTime: UInt32?,
        heardRepeats: Int,
        sendCount: Int = 1,
        retryAttempt: Int,
        maxRetryAttempts: Int,
        deduplicationKey: String? = nil,
        linkPreviewURL: String? = nil,
        linkPreviewTitle: String? = nil,
        linkPreviewImageData: Data? = nil,
        linkPreviewIconData: Data? = nil,
        linkPreviewFetched: Bool = false,
        containsSelfMention: Bool = false,
        mentionSeen: Bool = false,
        timestampCorrected: Bool = false,
        senderTimestamp: UInt32? = nil,
        reactionSummary: String? = nil,
        routeType: RouteType? = nil,
        regionScope: String? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.contactID = contactID
        self.channelIndex = channelIndex
        self.text = text
        self.timestamp = timestamp
        self.createdAt = createdAt
        self.sortDate = sortDate ?? createdAt
        self.direction = direction
        self.status = status
        self.textType = textType
        self.ackCode = ackCode
        self.pathLength = pathLength
        self.snr = snr
        self.pathNodes = pathNodes
        self.senderKeyPrefix = senderKeyPrefix
        self.senderNodeName = senderNodeName
        self.isRead = isRead
        self.replyToID = replyToID
        self.roundTripTime = roundTripTime
        self.heardRepeats = heardRepeats
        self.sendCount = sendCount
        self.retryAttempt = retryAttempt
        self.maxRetryAttempts = maxRetryAttempts
        self.deduplicationKey = deduplicationKey
        self.linkPreviewURL = linkPreviewURL
        self.linkPreviewTitle = linkPreviewTitle
        self.linkPreviewImageData = linkPreviewImageData
        self.linkPreviewIconData = linkPreviewIconData
        self.linkPreviewFetched = linkPreviewFetched
        self.containsSelfMention = containsSelfMention
        self.mentionSeen = mentionSeen
        self.timestampCorrected = timestampCorrected
        self.senderTimestamp = senderTimestamp
        self.reactionSummary = reactionSummary
        self.routeType = routeType
        self.regionScope = regionScope
    }

    public var isOutgoing: Bool {
        direction == .outgoing
    }

    public var isChannelMessage: Bool {
        channelIndex != nil
    }

    /// Timestamp to use for reaction hash computation.
    /// Uses original sender timestamp if available (for incoming messages with corrected timestamps),
    /// otherwise uses the stored timestamp.
    public var reactionTimestamp: UInt32 {
        senderTimestamp ?? timestamp
    }

    public var isPending: Bool {
        status == .pending || status == .sending
    }

    public var hasFailed: Bool {
        status == .failed
    }

    /// Returns a new MessageDTO with the given mutations applied.
    public func copy(_ mutations: (inout MessageDTO) -> Void) -> MessageDTO {
        var copy = self
        mutations(&copy)
        return copy
    }

    /// Date used for display and sorting (local receive time)
    public var date: Date {
        createdAt
    }

    /// Date derived from the sender's device clock (may differ from `date` if the sender's clock is skewed)
    public var senderDate: Date {
        Date(timeIntervalSince1970: TimeInterval(timestamp))
    }

    /// Raw, uncorrected send time the sender stamped on the wire.
    /// Unlike `senderDate`, this is not clock-corrected, so a skewed
    /// sender clock surfaces its literal value.
    public var wireSentDate: Date {
        Date(timeIntervalSince1970: TimeInterval(senderTimestamp ?? timestamp))
    }

    /// Hop count decoded from pathLength (lower 6 bits)
    public var hopCount: Int {
        decodePathLen(pathLength)?.hopCount ?? Int(pathLength & 63)
    }

    /// Whether this message was flood-routed (broadcast).
    /// Priority: channelIndex (channels are always flood) → routeType from RxLog → pathLength inference.
    public var isFloodRouted: Bool {
        if channelIndex != nil { return true }
        if let routeType { return routeType == .flood || routeType == .tcFlood }
        return pathLength != 0xFF
    }

    /// Whether this message was direct-routed (pre-built path, hops consumed in transit).
    public var isDirectRouted: Bool {
        !isFloodRouted
    }

    /// Hash size per hop in bytes (1, 2, or 3), derived from pathLength upper 2 bits
    public var pathHashSize: Int {
        decodePathLen(pathLength)?.hashSize ?? 1
    }

    /// Hash size per hop in bytes (1, 2, or 3) when the path length byte encodes a
    /// valid hash mode; nil for reserved modes or the no-path marker (0xFF).
    public var pathHashSizeIfKnown: Int? {
        decodePathLen(pathLength)?.hashSize
    }

    /// Path nodes as hex strings for display, chunked by hash size
    public var pathNodesHex: [String] {
        guard let pathNodes else { return [] }
        let size = pathHashSize
        return stride(from: 0, to: pathNodes.count, by: size).compactMap { start in
            let end = min(start + size, pathNodes.count)
            return pathNodes[start..<end].uppercaseHexString()
        }
    }

    /// Path as arrow-separated string (e.g., "A3 → 7F → 42")
    public var pathString: String {
        pathNodesHex.joined(separator: " → ")
    }

    /// Path as comma-separated string for clipboard (e.g., "A3,7F,42")
    public var pathStringForClipboard: String {
        pathNodesHex.joined(separator: ",")
    }

    // MARK: - Same-Sender Reordering

    /// Maximum time window (in seconds) within which consecutive messages from the same sender
    /// are re-sorted by sender timestamp to preserve intended send order.
    private static let sameSenderReorderWindow: TimeInterval = 5

    /// Reorders messages within narrow same-sender clusters by sender timestamp.
    ///
    /// Expects input already sorted by `sortDate` (the display sort key). When multiple
    /// messages from the same sender fall within a short window, mesh relay may deliver them
    /// out of order. This function detects those clusters and re-sorts them by the sender's
    /// claimed timestamp to restore the intended conversation order. The cluster window is
    /// measured on `sortDate` — the same key the input is sorted by — so it stays non-negative
    /// and cannot pull together rows that are far apart on the display axis.
    public static func reorderSameSenderClusters(_ messages: [MessageDTO]) -> [MessageDTO] {
        guard messages.count > 1 else { return messages }

        var result = messages
        var clusterStart = 0

        while clusterStart < result.count {
            var clusterEnd = clusterStart + 1

            // Extend the cluster while consecutive messages match the same sender/direction
            // and fall within the reorder window
            while clusterEnd < result.count {
                let gap = result[clusterEnd].sortDate.timeIntervalSince(result[clusterEnd - 1].sortDate)
                guard isSameSender(result[clusterEnd], result[clusterEnd - 1]),
                      gap <= sameSenderReorderWindow else { break }
                clusterEnd += 1
            }

            // Sort the cluster by sender timestamp if it contains more than one message
            if clusterEnd - clusterStart > 1 {
                let sorted = result[clusterStart..<clusterEnd].sorted {
                    if $0.timestamp != $1.timestamp { return $0.timestamp < $1.timestamp }
                    return $0.createdAt < $1.createdAt
                }
                result.replaceSubrange(clusterStart..<clusterEnd, with: sorted)
            }

            clusterStart = clusterEnd
        }

        return result
    }

    private static func isSameSender(_ a: MessageDTO, _ b: MessageDTO) -> Bool {
        guard a.direction == b.direction else { return false }
        guard a.isChannelMessage == b.isChannelMessage else { return false }

        // For channel messages, compare sender node name (nil = unknown, treat as different).
        // senderNodeName isn't unique — two users with the same name may be falsely clustered.
        if a.isChannelMessage {
            guard let nameA = a.senderNodeName, let nameB = b.senderNodeName else { return false }
            return nameA == nameB
        }

        // For DMs, same-direction messages share the same sender
        return true
    }
}

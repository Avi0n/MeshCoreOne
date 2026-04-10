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
        [\.deviceID, \.channelIndex, \.createdAt],
        [\.deviceID, \.channelIndex, \.timestamp],
        [\.contactID, \.createdAt],
        [\.contactID, \.containsSelfMention, \.mentionSeen],
        [\.deviceID, \.channelIndex, \.containsSelfMention, \.mentionSeen],
        [\.deduplicationKey]
    )

    /// Unique message identifier
    @Attribute(.unique)
    public var id: UUID

    /// The device this message belongs to
    public var deviceID: UUID

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

    /// Heard repeats for this message (cascade delete)
    @Relationship(deleteRule: .cascade, inverse: \MessageRepeat.message)
    public var repeats: [MessageRepeat]?

    public init(
        id: UUID = UUID(),
        deviceID: UUID,
        contactID: UUID? = nil,
        channelIndex: UInt8? = nil,
        text: String,
        timestamp: UInt32 = 0,
        createdAt: Date = Date(),
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
        routeTypeRawValue: Int = -1
    ) {
        self.id = id
        self.deviceID = deviceID
        self.contactID = contactID
        self.channelIndex = channelIndex
        self.text = text
        self.timestamp = timestamp > 0 ? timestamp : UInt32(createdAt.timeIntervalSince1970)
        self.createdAt = createdAt
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
public struct MessageDTO: Sendable, Equatable, Hashable, Identifiable {
    public var id: UUID
    public var deviceID: UUID
    public var contactID: UUID?
    public var channelIndex: UInt8?
    public var text: String
    public var timestamp: UInt32
    public var createdAt: Date
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

    public init(from message: Message) {
        self.id = message.id
        self.deviceID = message.deviceID
        self.contactID = message.contactID
        self.channelIndex = message.channelIndex
        self.text = message.text
        self.timestamp = message.timestamp
        self.createdAt = message.createdAt
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
        self.linkPreviewImageData = message.linkPreviewImageData
        self.linkPreviewIconData = message.linkPreviewIconData
        self.linkPreviewFetched = message.linkPreviewFetched
        self.containsSelfMention = message.containsSelfMention
        self.mentionSeen = message.mentionSeen
        self.timestampCorrected = message.timestampCorrected
        self.senderTimestamp = message.senderTimestamp
        self.reactionSummary = message.reactionSummary
        self.routeType = UInt8(exactly: message.routeTypeRawValue)
            .flatMap(RouteType.init(rawValue:))
    }

    /// Memberwise initializer for creating DTOs directly
    public init(
        id: UUID,
        deviceID: UUID,
        contactID: UUID?,
        channelIndex: UInt8?,
        text: String,
        timestamp: UInt32,
        createdAt: Date,
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
        routeType: RouteType? = nil
    ) {
        self.id = id
        self.deviceID = deviceID
        self.contactID = contactID
        self.channelIndex = channelIndex
        self.text = text
        self.timestamp = timestamp
        self.createdAt = createdAt
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

    /// Path nodes as hex strings for display, chunked by hash size
    public var pathNodesHex: [String] {
        guard let pathNodes else { return [] }
        let size = pathHashSize
        return stride(from: 0, to: pathNodes.count, by: size).compactMap { start in
            let end = min(start + size, pathNodes.count)
            return pathNodes[start..<end].hexString()
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
    /// Messages are sorted by receive time (`createdAt`) for display. However, when multiple
    /// messages from the same sender arrive within a short window, mesh relay may deliver them
    /// out of order. This function detects those clusters and re-sorts them by the sender's
    /// claimed timestamp to restore the intended conversation order.
    public static func reorderSameSenderClusters(_ messages: [MessageDTO]) -> [MessageDTO] {
        guard messages.count > 1 else { return messages }

        var result = messages
        var clusterStart = 0

        while clusterStart < result.count {
            var clusterEnd = clusterStart + 1

            // Extend the cluster while consecutive messages match the same sender/direction
            // and fall within the reorder window
            while clusterEnd < result.count {
                let gap = result[clusterEnd].createdAt.timeIntervalSince(result[clusterEnd - 1].createdAt)
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

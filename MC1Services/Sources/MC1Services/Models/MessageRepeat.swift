import Foundation
import MeshCore
import SwiftData

/// Represents a single heard repeat of a sent channel message.
/// Each repeat is an observation of the message being re-broadcast by a repeater.
@Model
final class MessageRepeat {
    #Index<MessageRepeat>(
        [\.messageID, \.receivedAt],
        [\.rxLogEntryID]
    )

    @Attribute(.unique)
    var id: UUID

    /// The parent message (cascade delete when message is deleted)
    var message: Message?

    /// The message ID (kept for queries, matches message.id)
    var messageID: UUID

    /// When this repeat was received by the companion radio
    var receivedAt: Date

    /// Repeater public key prefixes (1–3 bytes per hop depending on hash mode)
    var pathNodes: Data

    /// Encoded path length byte (upper 2 bits = hash mode, lower 6 bits = hop count)
    var pathLength: UInt8 = 0

    /// Signal-to-noise ratio in dB
    var snr: Double?

    /// Received signal strength indicator in dBm
    var rssi: Int?

    /// Link to RxLogEntry for raw packet details
    var rxLogEntryID: UUID?

    init(
        id: UUID = UUID(),
        message: Message? = nil,
        messageID: UUID,
        receivedAt: Date = Date(),
        pathNodes: Data,
        pathLength: UInt8 = 0,
        snr: Double? = nil,
        rssi: Int? = nil,
        rxLogEntryID: UUID? = nil
    ) {
        self.id = id
        self.message = message
        self.messageID = messageID
        self.receivedAt = receivedAt
        self.pathNodes = pathNodes
        self.pathLength = pathLength
        self.snr = snr
        self.rssi = rssi
        self.rxLogEntryID = rxLogEntryID
    }

    /// Builds a model instance directly from a DTO. The parent `message`
    /// relationship is passed separately because the caller looks it up in
    /// bulk before iterating.
    convenience init(dto: MessageRepeatDTO, message: Message?) {
        self.init(
            id: dto.id,
            message: message,
            messageID: dto.messageID,
            receivedAt: dto.receivedAt,
            pathNodes: dto.pathNodes,
            pathLength: dto.pathLength,
            snr: dto.snr,
            rssi: dto.rssi,
            rxLogEntryID: dto.rxLogEntryID
        )
    }
}

// MARK: - DTO

/// Sendable DTO for cross-actor transfer of MessageRepeat data.
public struct MessageRepeatDTO: Sendable, Identifiable, Equatable, Hashable, Codable {
    public let id: UUID
    public var messageID: UUID
    public let receivedAt: Date
    public let pathNodes: Data
    public let pathLength: UInt8
    public let snr: Double?
    public let rssi: Int?
    public let rxLogEntryID: UUID?

    init(from model: MessageRepeat) {
        self.id = model.id
        self.messageID = model.messageID
        self.receivedAt = model.receivedAt
        self.pathNodes = model.pathNodes
        self.pathLength = model.pathLength
        self.snr = model.snr
        self.rssi = model.rssi
        self.rxLogEntryID = model.rxLogEntryID
    }

    public init(
        id: UUID = UUID(),
        messageID: UUID,
        receivedAt: Date,
        pathNodes: Data,
        pathLength: UInt8 = 0,
        snr: Double?,
        rssi: Int?,
        rxLogEntryID: UUID?
    ) {
        self.id = id
        self.messageID = messageID
        self.receivedAt = receivedAt
        self.pathNodes = pathNodes
        self.pathLength = pathLength
        self.snr = snr
        self.rssi = rssi
        self.rxLogEntryID = rxLogEntryID
    }

    // MARK: - Computed Properties

    /// Hash size per hop in bytes (1, 2, or 3), derived from pathLength upper 2 bits
    public var hashSize: Int {
        decodePathLen(pathLength)?.hashSize ?? 1
    }

    /// Last repeater's public key prefix bytes (the node we heard from), or nil if direct
    public var repeaterHash: Data? {
        guard !pathNodes.isEmpty else { return nil }
        let size = hashSize
        guard pathNodes.count >= size else { return pathNodes }
        return pathNodes.suffix(size)
    }

    /// Number of hops in the path (1 = direct from repeater, 2+ = multi-hop)
    public var hopCount: Int {
        let size = hashSize
        guard size > 0 else { return pathNodes.count }
        return pathNodes.count / size
    }

    /// Repeater hash formatted as hex (e.g., "31" for 1-byte, "31A7" for 2-byte)
    public var repeaterHashFormatted: String {
        guard let hash = repeaterHash else { return "00" }
        return hash.uppercaseHexString()
    }

    /// Path nodes as hex strings for display, chunked by hash size
    public var pathNodesHex: [String] {
        let size = hashSize
        guard size > 0 else { return pathNodes.map { String(format: "%02X", $0) } }
        return stride(from: 0, to: pathNodes.count, by: size).compactMap { start in
            let end = min(start + size, pathNodes.count)
            return pathNodes[start..<end].uppercaseHexString()
        }
    }

    /// Classified signal quality based on SNR thresholds.
    public var snrQuality: SNRQuality { SNRQuality(snr: snr) }

    /// SNR mapped to 0-1 for signal bars variableValue.
    public var snrLevel: Double { snrQuality.barLevel }

    /// RSSI formatted for display
    public var rssiFormatted: String {
        guard let rssi = rssi else { return "—" }
        return "\(rssi) dBm"
    }

    /// SNR formatted for display
    public var snrFormatted: String {
        guard let snr = snr else { return "—" }
        return snr.formatted(.number.precision(.fractionLength(1))) + " dB"
    }
}

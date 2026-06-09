// MC1Services/Sources/MC1Services/Models/RxLogEntry.swift
import Foundation
import MeshCore
import SwiftData

/// SwiftData model for persisted RX log packets.
@Model
public final class RxLogEntry {
    #Index<RxLogEntry>(
        [\.channelIndex, \.senderTimestamp]
    )

    @Attribute(.unique)
    public var id: UUID

    @Attribute(originalName: "deviceID")
    public var radioID: UUID

    public var receivedAt: Date

    // From MeshCore ParsedRxLogData
    public var snr: Double?
    public var rssi: Int?
    public var routeType: Int
    public var payloadType: Int
    public var payloadVersion: Int
    public var transportCode: Data?
    public var pathLength: Int
    public var pathNodes: Data  // Raw bytes, 1 byte per hop
    public var packetPayload: Data
    public var rawPayload: Data

    // Correlation key for "heard repeats"
    public var packetHash: String

    // App-level decoding
    @Attribute(originalName: "channelHash")
    public var channelIndex: Int?
    public var channelName: String?
    public var decryptStatus: Int
    public var fromContactName: String?
    public var toContactName: String?

    /// Sender's timestamp from decrypted payload (Unix epoch seconds).
    /// Only available for successfully decrypted channel messages.
    public var senderTimestamp: Int?

    /// Resolved flood region the sender transmitted under, derived from
    /// `transport_codes[0]` at receive time. Nil when no known region matches.
    /// Local-only: not part of any backup envelope.
    public var regionScope: String?

    /// Raw 4-bit payload-type nibble from the wire header. Persisted so the
    /// region resolver can replay the exact firmware HMAC input on back-fill,
    /// even for header values that map to `PayloadType.unknown`.
    public var payloadTypeBits: Int = 0

    // Privacy: Never persisted — decrypted on demand
    @Transient
    public var decodedText: String?

    public init(
        id: UUID = UUID(),
        radioID: UUID,
        receivedAt: Date = Date(),
        snr: Double? = nil,
        rssi: Int? = nil,
        routeType: Int,
        payloadType: Int,
        payloadVersion: Int,
        transportCode: Data? = nil,
        pathLength: Int,
        pathNodes: Data,
        packetPayload: Data,
        rawPayload: Data,
        packetHash: String,
        channelIndex: Int? = nil,
        channelName: String? = nil,
        decryptStatus: Int = DecryptStatus.notApplicable.rawValue,
        fromContactName: String? = nil,
        toContactName: String? = nil,
        senderTimestamp: Int? = nil,
        regionScope: String? = nil,
        payloadTypeBits: Int = 0
    ) {
        self.id = id
        self.radioID = radioID
        self.receivedAt = receivedAt
        self.snr = snr
        self.rssi = rssi
        self.routeType = routeType
        self.payloadType = payloadType
        self.payloadVersion = payloadVersion
        self.transportCode = transportCode
        self.pathLength = pathLength
        self.pathNodes = pathNodes
        self.packetPayload = packetPayload
        self.rawPayload = rawPayload
        self.packetHash = packetHash
        self.channelIndex = channelIndex
        self.channelName = channelName
        self.decryptStatus = decryptStatus
        self.fromContactName = fromContactName
        self.toContactName = toContactName
        self.senderTimestamp = senderTimestamp
        self.regionScope = regionScope
        self.payloadTypeBits = payloadTypeBits
    }
}

/// Sendable DTO for cross-actor transfer of RxLogEntry data.
public struct RxLogEntryDTO: Sendable, Identifiable, Equatable, Hashable {
    public let id: UUID
    public var radioID: UUID
    public let receivedAt: Date
    public let snr: Double?
    public let rssi: Int?
    public let routeType: RouteType
    public let payloadType: PayloadType
    public let payloadVersion: UInt8
    public let transportCode: Data?
    public let pathLength: UInt8
    public let pathNodes: Data
    public let packetPayload: Data
    public let rawPayload: Data
    public let packetHash: String
    public let channelIndex: UInt8?
    public let channelName: String?
    public let decryptStatus: DecryptStatus
    public let fromContactName: String?
    public let toContactName: String?

    /// Sender's timestamp from decrypted payload (Unix epoch seconds).
    /// Only available for successfully decrypted channel messages.
    /// Mutable to allow updating during re-decryption of older entries.
    public var senderTimestamp: UInt32?

    /// Resolved flood region the sender transmitted under. Local-only.
    public let regionScope: String?

    /// Raw 4-bit payload-type nibble from the wire header (matches firmware
    /// HMAC input). Persisted alongside `regionScope` for back-fill replay.
    public let payloadTypeBits: UInt8

    // Transient - set by UI layer after decryption
    public var decodedText: String?

    /// Initialize from SwiftData model.
    public init(from model: RxLogEntry) {
        self.id = model.id
        self.radioID = model.radioID
        self.receivedAt = model.receivedAt
        self.snr = model.snr
        self.rssi = model.rssi
        self.routeType = RouteType(rawValue: UInt8(model.routeType)) ?? .flood
        self.payloadType = PayloadType(rawValue: UInt8(model.payloadType)) ?? .unknown
        self.payloadVersion = UInt8(model.payloadVersion)
        self.transportCode = model.transportCode
        self.pathLength = UInt8(model.pathLength)
        self.pathNodes = model.pathNodes
        self.packetPayload = model.packetPayload
        self.rawPayload = model.rawPayload
        self.packetHash = model.packetHash
        self.channelIndex = model.channelIndex.map { UInt8($0) }
        self.channelName = model.channelName
        self.decryptStatus = DecryptStatus(rawValue: model.decryptStatus) ?? .notApplicable
        self.fromContactName = model.fromContactName
        self.toContactName = model.toContactName
        self.senderTimestamp = model.senderTimestamp.map { UInt32($0) }
        self.regionScope = model.regionScope
        self.payloadTypeBits = UInt8(model.payloadTypeBits & 0x0F)
        self.decodedText = model.decodedText
    }

    /// Initialize from ParsedRxLogData (for new entries).
    public init(
        id: UUID = UUID(),
        radioID: UUID,
        receivedAt: Date = Date(),
        from parsed: ParsedRxLogData,
        channelIndex: UInt8? = nil,
        channelName: String? = nil,
        decryptStatus: DecryptStatus = .notApplicable,
        fromContactName: String? = nil,
        toContactName: String? = nil,
        senderTimestamp: UInt32? = nil,
        regionScope: String? = nil,
        decodedText: String? = nil
    ) {
        self.id = id
        self.radioID = radioID
        self.receivedAt = receivedAt
        self.snr = parsed.snr
        self.rssi = parsed.rssi
        self.routeType = parsed.routeType
        self.payloadType = parsed.payloadType
        self.payloadVersion = parsed.payloadVersion
        self.transportCode = parsed.transportCode
        self.pathLength = parsed.pathLength
        self.pathNodes = Data(parsed.pathNodes)
        self.packetPayload = parsed.packetPayload
        self.rawPayload = parsed.rawPayload
        self.packetHash = parsed.packetHash
        self.channelIndex = channelIndex
        self.channelName = channelName
        self.decryptStatus = decryptStatus
        self.fromContactName = fromContactName
        self.toContactName = toContactName
        self.senderTimestamp = senderTimestamp
        self.regionScope = regionScope
        self.payloadTypeBits = parsed.payloadTypeBits
        self.decodedText = decodedText
    }

    // MARK: - Computed Properties

    /// Hash size per hop in bytes (1, 2, or 3), derived from pathLength upper 2 bits.
    public var pathHashSize: Int {
        return decodePathLen(pathLength)?.hashSize ?? 1
    }

    /// Hop count decoded from pathLength.
    public var hopCount: Int {
        return decodePathLen(pathLength)?.hopCount ?? 0
    }

    /// Target node hashes extracted from TRACE payload.
    /// Layout: [tag:4][auth:4][flags:1][hashes...], hash size from flags lower 2 bits.
    public var traceTargetHashes: [Data]? {
        guard payloadType == .trace, packetPayload.count > 9 else { return nil }
        let pathSz = Int(packetPayload[8] & 0x03)
        let hashSize = 1 << pathSz
        let hashBytes = packetPayload.dropFirst(9)
        guard !hashBytes.isEmpty, hashBytes.count % hashSize == 0 else { return nil }
        return stride(from: hashBytes.startIndex, to: hashBytes.endIndex, by: hashSize).map { start in
            Data(hashBytes[start..<start + hashSize])
        }
    }

    /// Sender public key prefix for direct text messages.
    public var senderPrefix: Data? {
        guard !isFlood, payloadType == .textMessage else { return nil }
        let dmPrefixSize = 1
        guard packetPayload.count >= dmPrefixSize * 2 else { return nil }
        return Data(packetPayload[dmPrefixSize..<dmPrefixSize * 2])
    }

    /// Recipient public key prefix for direct text messages.
    public var recipientPrefix: Data? {
        guard !isFlood, payloadType == .textMessage else { return nil }
        let dmPrefixSize = 1
        guard packetPayload.count >= dmPrefixSize * 2 else { return nil }
        return Data(packetPayload[0..<dmPrefixSize])
    }

    /// Whether this is a flood-type route.
    public var isFlood: Bool {
        routeType == .flood || routeType == .tcFlood
    }

    // MARK: - Signal Quality

    /// Classified signal quality based on SNR thresholds.
    public var snrQuality: SNRQuality { SNRQuality(snr: snr) }

    /// SNR mapped to 0-1 for SF Symbol cellularbars variableValue.
    public var snrLevel: Double { snrQuality.barLevel }

    /// Human-readable SNR quality label for accessibility.
    public var snrQualityLabel: String { snrQuality.qualityLabel }

    /// Formatted SNR string (no label, includes sign for negative).
    public var snrDisplayString: String? {
        guard let snr else { return nil }
        return snr.formatted(.number.precision(.fractionLength(1))) + " dB"
    }

    /// Route type display - "FLOOD" or "DIRECT" (simplified from TC variants).
    public var routeTypeSimple: String {
        isFlood ? "FLOOD" : "DIRECT"
    }

    // MARK: - Hashable

    public func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

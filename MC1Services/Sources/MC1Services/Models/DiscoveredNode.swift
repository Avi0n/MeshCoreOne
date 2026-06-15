import Foundation
import MeshCore
import SwiftData

/// Represents a node discovered via advertisement.
/// Separate from Contact - this is ephemeral, app-only, capped at 1000 per device.
@Model
final class DiscoveredNode {
    #Index<DiscoveredNode>(
        [\.radioID, \.publicKey],
        [\.radioID, \.lastHeard]
    )

    @Attribute(.unique)
    var id: UUID

    /// Parent device ID
    @Attribute(originalName: "deviceID")
    var radioID: UUID

    /// 32-byte public key identifier
    var publicKey: Data

    /// Advertised node name
    var name: String

    /// Node type (1=chat, 2=repeater, 3=room)
    var typeRawValue: UInt8

    /// When we last received an advertisement from this node
    var lastHeard: Date

    /// Firmware advertisement timestamp
    var lastAdvertTimestamp: UInt32

    /// Node latitude
    var latitude: Double

    /// Node longitude
    var longitude: Double

    /// Encoded routing path length (0xFF = flood)
    var outPathLength: UInt8

    /// Routing path data (up to 64 bytes)
    var outPath: Data

    init(
        id: UUID = UUID(),
        radioID: UUID,
        publicKey: Data,
        name: String,
        typeRawValue: UInt8,
        lastHeard: Date = Date(),
        lastAdvertTimestamp: UInt32,
        latitude: Double = 0,
        longitude: Double = 0,
        outPathLength: UInt8 = PacketBuilder.floodPathSentinel,
        outPath: Data = Data()
    ) {
        self.id = id
        self.radioID = radioID
        self.publicKey = publicKey
        self.name = name
        self.typeRawValue = typeRawValue
        self.lastHeard = lastHeard
        self.lastAdvertTimestamp = lastAdvertTimestamp
        self.latitude = latitude
        self.longitude = longitude
        self.outPathLength = outPathLength
        self.outPath = outPath
    }
}

// MARK: - Sendable DTO

/// A sendable snapshot of DiscoveredNode for cross-actor transfers
public struct DiscoveredNodeDTO: Sendable, Equatable, Identifiable, RepeaterResolvable {
    public let id: UUID
    public let radioID: UUID
    public let publicKey: Data
    public let name: String
    public let typeRawValue: UInt8
    public let lastHeard: Date
    public let lastAdvertTimestamp: UInt32
    public let latitude: Double
    public let longitude: Double
    public let outPathLength: UInt8
    public let outPath: Data

    public var nodeType: ContactType {
        ContactType(rawValue: typeRawValue) ?? .chat
    }

    public var hasLocation: Bool {
        latitude != 0 || longitude != 0
    }

    public var isFloodRouted: Bool {
        outPathLength == PacketBuilder.floodPathSentinel
    }

    public var pathHashSize: Int {
        decodePathLen(outPathLength)?.hashSize ?? 1
    }

    public var pathHopCount: Int {
        decodePathLen(outPathLength)?.hopCount ?? 0
    }

    public var pathByteLength: Int {
        decodePathLen(outPathLength)?.byteLength ?? 0
    }

    public var pathNodesHex: [String] {
        outPath.prefix(pathByteLength).pathHops(hashSize: pathHashSize).map(\.hex)
    }

    public var recencyDate: Date { lastHeard }
    public var resolvableName: String { name }

    public init(
        id: UUID,
        radioID: UUID,
        publicKey: Data,
        name: String,
        typeRawValue: UInt8,
        lastHeard: Date,
        lastAdvertTimestamp: UInt32,
        latitude: Double,
        longitude: Double,
        outPathLength: UInt8,
        outPath: Data
    ) {
        self.id = id
        self.radioID = radioID
        self.publicKey = publicKey
        self.name = name
        self.typeRawValue = typeRawValue
        self.lastHeard = lastHeard
        self.lastAdvertTimestamp = lastAdvertTimestamp
        self.latitude = latitude
        self.longitude = longitude
        self.outPathLength = outPathLength
        self.outPath = outPath
    }

    init(from node: DiscoveredNode) {
        self.id = node.id
        self.radioID = node.radioID
        self.publicKey = node.publicKey
        self.name = node.name
        self.typeRawValue = node.typeRawValue
        self.lastHeard = node.lastHeard
        self.lastAdvertTimestamp = node.lastAdvertTimestamp
        self.latitude = node.latitude
        self.longitude = node.longitude
        self.outPathLength = node.outPathLength
        self.outPath = node.outPath
    }
}

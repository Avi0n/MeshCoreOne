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

  /// Hops the advert traversed to reach this phone (inbound), decoded from the heard
  /// RX-log packet's path length. nil = never heard via an advert RX-log entry; 0 = heard
  /// directly. Distinct from outPath/outPathLength, the route used to send to this node.
  var inboundHopCount: Int?

  /// The firmware advert timestamp that was current when inboundHopCount was last written.
  /// Paired with inboundHopCount to implement latest-advert semantics: a newer timestamp
  /// always replaces the stored count; equal timestamps keep the closest copy of that broadcast.
  var inboundHopAdvertTimestamp: UInt32?

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
    outPath: Data = Data(),
    inboundHopCount: Int? = nil,
    inboundHopAdvertTimestamp: UInt32? = nil
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
    self.inboundHopCount = inboundHopCount
    self.inboundHopAdvertTimestamp = inboundHopAdvertTimestamp
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
  public let inboundHopCount: Int?
  public let inboundHopAdvertTimestamp: UInt32?

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

  /// The hop count to surface in the UI: the deliberately-set out-path hops when a route exists,
  /// otherwise the passively-heard inbound advert hops stored on this row. nil when flood-routed
  /// and no advert hop count is known.
  public var displayedHopCount: Int? {
    isFloodRouted ? inboundHopCount : pathHopCount
  }

  public var pathByteLength: Int {
    decodePathLen(outPathLength)?.byteLength ?? 0
  }

  public var pathNodesHex: [String] {
    outPath.prefix(pathByteLength).pathHops(hashSize: pathHashSize).map(\.hex)
  }

  public var recencyDate: Date {
    lastHeard
  }

  public var resolvableName: String {
    name
  }

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
    outPath: Data,
    inboundHopCount: Int?,
    inboundHopAdvertTimestamp: UInt32?
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
    self.inboundHopCount = inboundHopCount
    self.inboundHopAdvertTimestamp = inboundHopAdvertTimestamp
  }

  init(from node: DiscoveredNode) {
    id = node.id
    radioID = node.radioID
    publicKey = node.publicKey
    name = node.name
    typeRawValue = node.typeRawValue
    lastHeard = node.lastHeard
    lastAdvertTimestamp = node.lastAdvertTimestamp
    latitude = node.latitude
    longitude = node.longitude
    outPathLength = node.outPathLength
    outPath = node.outPath
    inboundHopCount = node.inboundHopCount
    inboundHopAdvertTimestamp = node.inboundHopAdvertTimestamp
  }
}

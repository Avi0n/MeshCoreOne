import Foundation
import MeshCore
import SwiftData

/// Capture-rate policy for node status snapshots.
enum NodeSnapshotPolicy {
  /// Minimum interval between persisted snapshots for the same node.
  /// A status, telemetry, or neighbor capture arriving within this window of
  /// the latest snapshot enriches that row rather than inserting a new one.
  static let minimumInterval: TimeInterval = 15 * 60
}

/// The radio/room status fields captured in a snapshot, clamped to their
/// storage types. Bundling them into one value keeps the save and backfill
/// paths in lockstep and performs the lossy `Int16` conversions exactly once.
public struct NodeStatusMetrics: Sendable, Equatable {
  public let batteryMillivolts: UInt16?
  public let lastSNR: Double?
  public let lastRSSI: Int16?
  public let noiseFloor: Int16?
  public let uptimeSeconds: UInt32?
  public let rxAirtimeSeconds: UInt32?
  public let packetsSent: UInt32?
  public let packetsReceived: UInt32?
  public let receiveErrors: UInt32?
  public let postedCount: UInt16?
  public let postPushCount: UInt16?

  public init(
    batteryMillivolts: UInt16?,
    lastSNR: Double?,
    lastRSSI: Int16?,
    noiseFloor: Int16?,
    uptimeSeconds: UInt32?,
    rxAirtimeSeconds: UInt32?,
    packetsSent: UInt32?,
    packetsReceived: UInt32?,
    receiveErrors: UInt32?,
    postedCount: UInt16? = nil,
    postPushCount: UInt16? = nil
  ) {
    self.batteryMillivolts = batteryMillivolts
    self.lastSNR = lastSNR
    self.lastRSSI = lastRSSI
    self.noiseFloor = noiseFloor
    self.uptimeSeconds = uptimeSeconds
    self.rxAirtimeSeconds = rxAirtimeSeconds
    self.packetsSent = packetsSent
    self.packetsReceived = packetsReceived
    self.receiveErrors = receiveErrors
    self.postedCount = postedCount
    self.postPushCount = postPushCount
  }

  /// Build metrics from a status response. `lastRSSI` and `noiseFloor` saturate
  /// into `Int16` so an out-of-range firmware value clamps to the axis bound.
  /// Role-specific fields are supplied by the caller: repeaters pass
  /// `rxAirtimeSeconds`/`receiveErrors`, rooms pass `postedCount`/`postPushCount`.
  public init(
    status: RemoteNodeStatus,
    rxAirtimeSeconds: UInt32? = nil,
    receiveErrors: UInt32? = nil,
    postedCount: UInt16? = nil,
    postPushCount: UInt16? = nil
  ) {
    self.init(
      batteryMillivolts: status.batteryMillivolts,
      lastSNR: status.lastSNR,
      lastRSSI: Int16(clamping: status.lastRSSI),
      noiseFloor: Int16(clamping: status.noiseFloor),
      uptimeSeconds: status.uptimeSeconds,
      rxAirtimeSeconds: rxAirtimeSeconds,
      packetsSent: status.packetsSent,
      packetsReceived: status.packetsReceived,
      receiveErrors: receiveErrors,
      postedCount: postedCount,
      postPushCount: postPushCount
    )
  }
}

/// Codable entry representing a single neighbor's state at snapshot time.
public struct NeighborSnapshotEntry: Codable, Sendable, Equatable {
  public let publicKeyPrefix: Data
  public let snr: Double
  public let secondsAgo: Int

  public init(publicKeyPrefix: Data, snr: Double, secondsAgo: Int) {
    self.publicKeyPrefix = publicKeyPrefix
    self.snr = snr
    self.secondsAgo = secondsAgo
  }
}

/// Codable entry representing a single telemetry reading at snapshot time.
public struct TelemetrySnapshotEntry: Codable, Sendable, Equatable {
  public let channel: Int
  public let type: String
  public let value: Double

  public init(channel: Int, type: String, value: Double) {
    self.channel = channel
    self.type = type
    self.value = value
  }
}

/// Point-in-time snapshot of a remote node's status, captured when the user views it.
@Model
final class NodeStatusSnapshot {
  #Index<NodeStatusSnapshot>([\.nodePublicKey, \.timestamp])

  @Attribute(.unique)
  var id: UUID

  /// When this snapshot was captured
  var timestamp: Date

  /// The node's full public key (32 bytes) -- links to RemoteNodeSession
  var nodePublicKey: Data

  // MARK: - Radio metrics

  // Intentionally excluded: txQueueLength, airtime, sentFlood, sentDirect,
  // receivedFlood, receivedDirect, fullEvents, directDuplicates, floodDuplicates

  var batteryMillivolts: UInt16?
  var lastSNR: Double?
  var lastRSSI: Int16?
  var noiseFloor: Int16?
  var uptimeSeconds: UInt32?
  var rxAirtimeSeconds: UInt32?
  var packetsSent: UInt32?
  var packetsReceived: UInt32?
  var receiveErrors: UInt32?

  // MARK: - Room server metrics

  var postedCount: UInt16?
  var postPushCount: UInt16?

  // MARK: - Optional neighbor/telemetry data

  /// Neighbor data, only populated if the user expanded the neighbors section.
  var neighborSnapshots: [NeighborSnapshotEntry]?

  /// Telemetry data, only populated if the user expanded the telemetry section.
  var telemetryEntries: [TelemetrySnapshotEntry]?

  init(
    id: UUID = UUID(),
    timestamp: Date = .now,
    nodePublicKey: Data,
    batteryMillivolts: UInt16? = nil,
    lastSNR: Double? = nil,
    lastRSSI: Int16? = nil,
    noiseFloor: Int16? = nil,
    uptimeSeconds: UInt32? = nil,
    rxAirtimeSeconds: UInt32? = nil,
    packetsSent: UInt32? = nil,
    packetsReceived: UInt32? = nil,
    receiveErrors: UInt32? = nil,
    postedCount: UInt16? = nil,
    postPushCount: UInt16? = nil,
    neighborSnapshots: [NeighborSnapshotEntry]? = nil,
    telemetryEntries: [TelemetrySnapshotEntry]? = nil
  ) {
    self.id = id
    self.timestamp = timestamp
    self.nodePublicKey = nodePublicKey
    self.batteryMillivolts = batteryMillivolts
    self.lastSNR = lastSNR
    self.lastRSSI = lastRSSI
    self.noiseFloor = noiseFloor
    self.uptimeSeconds = uptimeSeconds
    self.rxAirtimeSeconds = rxAirtimeSeconds
    self.packetsSent = packetsSent
    self.packetsReceived = packetsReceived
    self.receiveErrors = receiveErrors
    self.postedCount = postedCount
    self.postPushCount = postPushCount
    self.neighborSnapshots = neighborSnapshots
    self.telemetryEntries = telemetryEntries
  }

  /// Apply captured status metrics onto this snapshot, leaving neighbor and
  /// telemetry enrichment untouched.
  func apply(_ metrics: NodeStatusMetrics) {
    batteryMillivolts = metrics.batteryMillivolts
    lastSNR = metrics.lastSNR
    lastRSSI = metrics.lastRSSI
    noiseFloor = metrics.noiseFloor
    uptimeSeconds = metrics.uptimeSeconds
    rxAirtimeSeconds = metrics.rxAirtimeSeconds
    packetsSent = metrics.packetsSent
    packetsReceived = metrics.packetsReceived
    receiveErrors = metrics.receiveErrors
    postedCount = metrics.postedCount
    postPushCount = metrics.postPushCount
  }

  /// Builds a model instance directly from a DTO.
  convenience init(dto: NodeStatusSnapshotDTO) {
    self.init(
      id: dto.id,
      timestamp: dto.timestamp,
      nodePublicKey: dto.nodePublicKey,
      batteryMillivolts: dto.batteryMillivolts,
      lastSNR: dto.lastSNR,
      lastRSSI: dto.lastRSSI,
      noiseFloor: dto.noiseFloor,
      uptimeSeconds: dto.uptimeSeconds,
      rxAirtimeSeconds: dto.rxAirtimeSeconds,
      packetsSent: dto.packetsSent,
      packetsReceived: dto.packetsReceived,
      receiveErrors: dto.receiveErrors,
      postedCount: dto.postedCount,
      postPushCount: dto.postPushCount,
      neighborSnapshots: dto.neighborSnapshots,
      telemetryEntries: dto.telemetryEntries
    )
  }
}

// MARK: - Sendable DTO

public struct NodeStatusSnapshotDTO: Sendable, Equatable, Identifiable, Codable {
  public let id: UUID
  public let timestamp: Date
  public let nodePublicKey: Data
  public let batteryMillivolts: UInt16?
  public let lastSNR: Double?
  public let lastRSSI: Int16?
  public let noiseFloor: Int16?
  public let uptimeSeconds: UInt32?
  public let rxAirtimeSeconds: UInt32?
  public let packetsSent: UInt32?
  public let packetsReceived: UInt32?
  public let receiveErrors: UInt32?
  public let postedCount: UInt16?
  public let postPushCount: UInt16?
  public let neighborSnapshots: [NeighborSnapshotEntry]?
  public let telemetryEntries: [TelemetrySnapshotEntry]?

  init(from model: NodeStatusSnapshot) {
    id = model.id
    timestamp = model.timestamp
    nodePublicKey = model.nodePublicKey
    batteryMillivolts = model.batteryMillivolts
    lastSNR = model.lastSNR
    lastRSSI = model.lastRSSI
    noiseFloor = model.noiseFloor
    uptimeSeconds = model.uptimeSeconds
    rxAirtimeSeconds = model.rxAirtimeSeconds
    packetsSent = model.packetsSent
    packetsReceived = model.packetsReceived
    receiveErrors = model.receiveErrors
    postedCount = model.postedCount
    postPushCount = model.postPushCount
    neighborSnapshots = model.neighborSnapshots
    telemetryEntries = model.telemetryEntries
  }

  public init(
    id: UUID = UUID(),
    timestamp: Date = .now,
    nodePublicKey: Data,
    batteryMillivolts: UInt16? = nil,
    lastSNR: Double? = nil,
    lastRSSI: Int16? = nil,
    noiseFloor: Int16? = nil,
    uptimeSeconds: UInt32? = nil,
    rxAirtimeSeconds: UInt32? = nil,
    packetsSent: UInt32? = nil,
    packetsReceived: UInt32? = nil,
    receiveErrors: UInt32? = nil,
    postedCount: UInt16? = nil,
    postPushCount: UInt16? = nil,
    neighborSnapshots: [NeighborSnapshotEntry]? = nil,
    telemetryEntries: [TelemetrySnapshotEntry]? = nil
  ) {
    self.id = id
    self.timestamp = timestamp
    self.nodePublicKey = nodePublicKey
    self.batteryMillivolts = batteryMillivolts
    self.lastSNR = lastSNR
    self.lastRSSI = lastRSSI
    self.noiseFloor = noiseFloor
    self.uptimeSeconds = uptimeSeconds
    self.rxAirtimeSeconds = rxAirtimeSeconds
    self.packetsSent = packetsSent
    self.packetsReceived = packetsReceived
    self.receiveErrors = receiveErrors
    self.postedCount = postedCount
    self.postPushCount = postPushCount
    self.neighborSnapshots = neighborSnapshots
    self.telemetryEntries = telemetryEntries
  }
}

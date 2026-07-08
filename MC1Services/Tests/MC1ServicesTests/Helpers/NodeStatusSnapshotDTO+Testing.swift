import Foundation
@testable import MC1Services

extension NodeStatusSnapshotDTO {
  /// Creates a NodeStatusSnapshotDTO with sensible test defaults.
  ///
  /// Usage:
  /// ```
  /// let snapshot = NodeStatusSnapshotDTO.testSnapshot(nodePublicKey: key)
  /// ```
  static func testSnapshot(
    id: UUID = UUID(),
    timestamp: Date = Date(),
    nodePublicKey: Data = Data(repeating: 0xDD, count: 32),
    batteryMillivolts: UInt16? = 3800,
    lastSNR: Double? = 9.0,
    lastRSSI: Int16? = -85,
    noiseFloor: Int16? = -110,
    uptimeSeconds: UInt32? = 3600,
    rxAirtimeSeconds: UInt32? = nil,
    packetsSent: UInt32? = nil,
    packetsReceived: UInt32? = nil,
    receiveErrors: UInt32? = nil,
    sentDirect: UInt32? = nil,
    sentFlood: UInt32? = nil,
    receivedDirect: UInt32? = nil,
    receivedFlood: UInt32? = nil,
    directDuplicates: UInt32? = nil,
    floodDuplicates: UInt32? = nil,
    neighborSnapshots: [NeighborSnapshotEntry]? = nil,
    telemetryEntries: [TelemetrySnapshotEntry]? = nil,
    latitude: Double? = nil,
    longitude: Double? = nil
  ) -> NodeStatusSnapshotDTO {
    NodeStatusSnapshotDTO(
      id: id,
      timestamp: timestamp,
      nodePublicKey: nodePublicKey,
      batteryMillivolts: batteryMillivolts,
      lastSNR: lastSNR,
      lastRSSI: lastRSSI,
      noiseFloor: noiseFloor,
      uptimeSeconds: uptimeSeconds,
      rxAirtimeSeconds: rxAirtimeSeconds,
      packetsSent: packetsSent,
      packetsReceived: packetsReceived,
      receiveErrors: receiveErrors,
      sentDirect: sentDirect,
      sentFlood: sentFlood,
      receivedDirect: receivedDirect,
      receivedFlood: receivedFlood,
      directDuplicates: directDuplicates,
      floodDuplicates: floodDuplicates,
      neighborSnapshots: neighborSnapshots,
      telemetryEntries: telemetryEntries,
      latitude: latitude,
      longitude: longitude
    )
  }
}

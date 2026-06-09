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
        neighborSnapshots: [NeighborSnapshotEntry]? = nil,
        telemetryEntries: [TelemetrySnapshotEntry]? = nil
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
            neighborSnapshots: neighborSnapshots,
            telemetryEntries: telemetryEntries
        )
    }
}

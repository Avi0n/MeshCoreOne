import Foundation

/// Store operations for node status snapshots.
public protocol NodeSnapshotPersisting: Actor {

    // swiftlint:disable function_parameter_count
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
    // swiftlint:enable function_parameter_count

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

    /// Atomically capture a status, telemetry, and/or neighbor snapshot for a node,
    /// enriching the latest in-window snapshot or inserting a new one. Returns the
    /// snapshot ID. The concrete `PersistenceStore` performs the read-modify-write
    /// in a single `@ModelActor` turn so concurrent captures cannot duplicate a row.
    func recordNodeStatusSnapshot(
        nodePublicKey: Data,
        status: NodeStatusMetrics?,
        telemetry: [TelemetrySnapshotEntry]?,
        neighbors: [NeighborSnapshotEntry]?
    ) async throws -> UUID

    /// Delete snapshots older than the given date
    func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws
}

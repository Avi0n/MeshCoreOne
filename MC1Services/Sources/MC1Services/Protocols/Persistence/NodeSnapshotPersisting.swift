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

public extension NodeSnapshotPersisting {
  /// The neighbor baseline: the previous neighbor-bearing snapshot (for the SNR
  /// delta) plus every neighbor prefix seen across history (for the "New" badge).
  /// Both derive from one fetch and one in-window cutoff, which excludes the
  /// reading being viewed so it is never diffed or matched against itself. Neighbor
  /// arrays are sparse (a snapshot holds them only when the user expanded the
  /// neighbors section), so status- or telemetry-only rows are skipped.
  func fetchNeighborBaseline(nodePublicKey: Data) async throws
    -> (previous: NodeStatusSnapshotDTO?, seenPrefixes: Set<Data>) {
    let all = try await fetchNodeStatusSnapshots(nodePublicKey: nodePublicKey, since: nil)
    let cutoff: Date = if let latest = all.last,
                          latest.timestamp.distance(to: .now) < NodeSnapshotPolicy.minimumInterval {
      latest.timestamp
    } else {
      .now
    }
    let history = all.filter { $0.timestamp < cutoff && $0.neighborSnapshots != nil }
    let seenPrefixes = Set(history.flatMap { $0.neighborSnapshots ?? [] }.map(\.publicKeyPrefix))
    return (previous: history.last, seenPrefixes: seenPrefixes)
  }

  /// The most recent snapshot carrying status fields before the given date, for the
  /// status delta. A neighbor- or telemetry-only capture inserts a row with no
  /// status; skipping those (the `uptimeSeconds` marker the in-window throttle uses)
  /// keeps such a row from blanking the delta. The in-window capture is kept, unlike
  /// the neighbor baseline: status is throttled and never overwrites itself, so an
  /// early reading in the current window is still a valid baseline.
  func fetchPreviousStatusSnapshot(nodePublicKey: Data, before: Date) async throws -> NodeStatusSnapshotDTO? {
    let all = try await fetchNodeStatusSnapshots(nodePublicKey: nodePublicKey, since: nil)
    return all.last { $0.timestamp < before && $0.uptimeSeconds != nil }
  }
}

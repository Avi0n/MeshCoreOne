import Foundation
import OSLog

/// Service for managing node status snapshots with throttled capture.
public actor NodeSnapshotService {
  private let dataStore: any NodeSnapshotPersisting
  private let logger = Logger(subsystem: "com.mc1", category: "NodeSnapshotService")

  init(dataStore: any NodeSnapshotPersisting) {
    self.dataStore = dataStore
  }

  /// Capture a status, telemetry, and/or neighbor reading for a node, enriching
  /// the latest in-window snapshot or inserting a new one. Returns the snapshot
  /// ID so callers can target later enrichment, or nil on persistence failure.
  /// The throttle check and the write are atomic in the store, so concurrent
  /// captures never duplicate an in-window row.
  public func recordSnapshot(
    nodePublicKey: Data,
    status: NodeStatusMetrics? = nil,
    telemetry: [TelemetrySnapshotEntry]? = nil,
    neighbors: [NeighborSnapshotEntry]? = nil,
    location: NodeLocationFix? = nil
  ) async -> UUID? {
    do {
      return try await dataStore.recordNodeStatusSnapshot(
        nodePublicKey: nodePublicKey,
        status: status,
        telemetry: telemetry,
        neighbors: neighbors,
        location: location
      )
    } catch {
      logger.error("Failed to record snapshot: \(error)")
      return nil
    }
  }

  /// The neighbor baseline for a node: the previous neighbor-bearing snapshot (for
  /// the SNR delta) plus every neighbor prefix seen across history (for the "New"
  /// badge). Skips status- or telemetry-only rows and the current in-window capture.
  public func neighborBaseline(for nodePublicKey: Data)
    async -> (previous: NodeStatusSnapshotDTO?, seenPrefixes: Set<Data>) {
    do {
      return try await dataStore.fetchNeighborBaseline(nodePublicKey: nodePublicKey)
    } catch {
      logger.error("Failed to fetch neighbor baseline: \(error)")
      return (nil, [])
    }
  }

  /// Fetch the most recent snapshot carrying status fields, for the status delta.
  /// Skips neighbor- or telemetry-only rows so the delta is taken against the
  /// previous actual status reading rather than blanking out.
  public func previousStatusSnapshot(for nodePublicKey: Data, before date: Date) async -> NodeStatusSnapshotDTO? {
    do {
      return try await dataStore.fetchPreviousStatusSnapshot(nodePublicKey: nodePublicKey, before: date)
    } catch {
      logger.error("Failed to fetch previous status snapshot: \(error)")
      return nil
    }
  }

  /// Fetch all snapshots for a node, optionally filtered by date range.
  public func fetchSnapshots(for nodePublicKey: Data, since: Date? = nil) async -> [NodeStatusSnapshotDTO] {
    do {
      return try await dataStore.fetchNodeStatusSnapshots(nodePublicKey: nodePublicKey, since: since)
    } catch {
      logger.error("Failed to fetch snapshots: \(error)")
      return []
    }
  }

  /// Delete snapshots older than the given date.
  public func pruneOldSnapshots(olderThan date: Date) async {
    do {
      try await dataStore.deleteOldNodeStatusSnapshots(olderThan: date)
      logger.info("Pruned snapshots older than \(date)")
    } catch {
      logger.error("Failed to prune old snapshots: \(error)")
    }
  }
}

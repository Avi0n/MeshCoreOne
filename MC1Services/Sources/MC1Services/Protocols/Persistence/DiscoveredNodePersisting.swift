import Foundation

/// Store operations for discovered (not yet added) mesh nodes.
public protocol DiscoveredNodePersisting: Actor {
  /// Insert or update a discovered node from an advertisement frame.
  /// Updates lastHeard timestamp if node already exists and `receivedAt` is not older.
  /// - Returns: Tuple of (DiscoveredNodeDTO, isNew) where isNew is true only if node was newly created
  func upsertDiscoveredNode(
    radioID: UUID,
    from frame: ContactFrame,
    receivedAt: Date
  ) async throws -> (node: DiscoveredNodeDTO, isNew: Bool)

  /// Insert or update a batch of discovered nodes for `radioID` in one store turn.
  func batchUpsertDiscoveredNodes(
    radioID: UUID,
    entries: [(frame: ContactFrame, receivedAt: Date)]
  ) async throws -> [(node: DiscoveredNodeDTO, isNew: Bool)]

  /// Stamp the inbound advert hop count onto an existing discovered node, keyed by public key.
  /// Tracks the latest advert: a newer advertTimestamp always replaces the stored count;
  /// equal timestamps keep the closest copy of that broadcast. No-op if no matching row exists;
  /// the next advert upsert creates it.
  func setInboundHopCount(radioID: UUID, publicKey: Data, hopCount: Int, advertTimestamp: UInt32?) async throws

  /// Fetch all discovered nodes for a device.
  func fetchDiscoveredNodes(radioID: UUID) async throws -> [DiscoveredNodeDTO]

  /// Delete a discovered node by ID.
  func deleteDiscoveredNode(id: UUID) async throws

  /// Clear all discovered nodes for a device.
  func clearDiscoveredNodes(radioID: UUID) async throws

  /// Batch fetch all contact public keys for efficient "added" state lookup.
  /// Returns public keys of confirmed (non-discovered) contacts only.
  func fetchContactPublicKeys(radioID: UUID) async throws -> Set<Data>
}

public extension DiscoveredNodePersisting {
  func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) async throws -> (node: DiscoveredNodeDTO, isNew: Bool) {
    try await upsertDiscoveredNode(radioID: radioID, from: frame, receivedAt: Date())
  }

  func batchUpsertDiscoveredNodes(
    radioID: UUID,
    entries: [(frame: ContactFrame, receivedAt: Date)]
  ) async throws -> [(node: DiscoveredNodeDTO, isNew: Bool)] {
    var results: [(node: DiscoveredNodeDTO, isNew: Bool)] = []
    results.reserveCapacity(entries.count)
    for entry in entries {
      try await results.append(upsertDiscoveredNode(
        radioID: radioID,
        from: entry.frame,
        receivedAt: entry.receivedAt
      ))
    }
    return results
  }
}

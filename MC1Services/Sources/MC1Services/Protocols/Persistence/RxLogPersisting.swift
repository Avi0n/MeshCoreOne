import Foundation

/// Store operations for RX log entries: persistence, lookup, and batch enrichment.
public protocol RxLogPersisting: Actor {
  // MARK: - RxLogEntry Lookup

  /// Find RxLogEntry matching an incoming message for path correlation.
  ///
  /// For channel messages: Correlates by channel index and sender timestamp.
  /// For direct messages: Correlates by sender timestamp and payload type.
  func findRxLogEntry(
    radioID: UUID,
    channelIndex: UInt8?,
    senderTimestamp: UInt32
  ) async throws -> RxLogEntryDTO?

  /// Find a DM RxLogEntry by matching the sender prefix byte in the packet payload.
  /// Fallback for when the primary timestamp-based lookup fails.
  func findRxLogEntryBySenderPrefix(
    radioID: UUID,
    senderPrefixByte: UInt8,
    receivedSince: Date
  ) async throws -> RxLogEntryDTO?

  // MARK: - RX Log

  /// Save a new RX log entry
  func saveRxLogEntry(_ dto: RxLogEntryDTO) async throws

  /// Fetch RX log entries for a device, most recent first
  func fetchRxLogEntries(radioID: UUID, limit: Int) async throws -> [RxLogEntryDTO]

  /// Clear all RX log entries for a device
  func clearRxLogEntries(radioID: UUID) async throws

  /// Delete oldest entries once the log materially exceeds the retention cap
  func pruneRxLogEntries(radioID: UUID, keepCount: Int, pruneThreshold: Int) async throws

  /// Fetch RX log entries that have a transport code but no resolved
  /// region yet, the back-fill candidate set
  func fetchEntriesWithMissingRegion(radioID: UUID) async throws -> [RxLogEntryDTO]

  /// Fetch recent RX log entries with a given decrypt status
  func fetchRecentEntriesByDecryptStatus(radioID: UUID, status: DecryptStatus, since: Date) async throws -> [RxLogEntryDTO]

  /// Batch update `regionScope` on RX log entries by id
  func batchUpdateRxLogRegion(updates: [(id: UUID, regionScope: String?)]) async throws

  /// Batch update RX log entries after successful decryption.
  /// Note: decodedText is transient and not persisted.
  func batchUpdateRxLogDecryption(
    _ updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)]
  ) async throws

  /// Batch update `regionScope` on incoming channel `Message` rows
  /// correlated by `(channelIndex, senderTimestamp)`
  func batchUpdateChannelMessageRegion(
    radioID: UUID,
    updates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?)]
  ) async throws

  /// Batch update `regionScope` on incoming DM `Message` rows
  /// correlated by `(senderPrefixByte, senderTimestamp)`
  func batchUpdateDMMessageRegion(
    radioID: UUID,
    updates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?)]
  ) async throws
}

// MARK: - Default Parameter Values

extension RxLogPersisting {
  /// Fetch RX log entries with the default limit of 500
  func fetchRxLogEntries(radioID: UUID) async throws -> [RxLogEntryDTO] {
    try await fetchRxLogEntries(radioID: radioID, limit: 500)
  }

  /// Prune RX log entries with the default retention cap of 1000 plus a 100-entry threshold
  func pruneRxLogEntries(radioID: UUID) async throws {
    try await pruneRxLogEntries(radioID: radioID, keepCount: 1000, pruneThreshold: 100)
  }
}

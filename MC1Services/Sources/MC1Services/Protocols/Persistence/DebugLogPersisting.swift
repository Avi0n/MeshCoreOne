import Foundation

/// Store operations for debug log entries.
public protocol DebugLogPersisting: Actor {
  /// Save a batch of debug log entries
  func saveDebugLogEntries(_ dtos: [DebugLogEntryDTO]) async throws

  /// Fetch debug log entries since a given date
  func fetchDebugLogEntries(since date: Date, limit: Int) async throws -> [DebugLogEntryDTO]

  /// Count all debug log entries
  func countDebugLogEntries() async throws -> Int

  /// Prune debug log entries older than the cutoff, then enforce a hard row ceiling
  func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws

  /// Clear all debug log entries
  func clearDebugLogEntries() async throws
}

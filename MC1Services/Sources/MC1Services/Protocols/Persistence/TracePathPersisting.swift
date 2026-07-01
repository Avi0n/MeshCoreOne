import Foundation

/// Store operations for saved trace paths and their runs.
public protocol TracePathPersisting: Actor {
  /// Fetch all saved trace paths for a device
  func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO]

  /// Fetch a single saved trace path by ID
  func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO?

  /// Create a new saved trace path
  func createSavedTracePath(radioID: UUID, name: String, pathBytes: Data, hashSize: Int, initialRun: TracePathRunDTO?) async throws -> SavedTracePathDTO

  /// Update a saved trace path's name
  func updateSavedTracePathName(id: UUID, name: String) async throws

  /// Delete a saved trace path
  func deleteSavedTracePath(id: UUID) async throws

  /// Append a run to a saved trace path
  func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws
}

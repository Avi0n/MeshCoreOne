import Foundation
import MeshCore

/// Protocol for ContactService to enable testability of SyncCoordinator.
///
/// This protocol abstracts the contact sync operations used by SyncCoordinator,
/// allowing it to be tested with mock implementations.
///
/// ## Usage
///
/// Services can accept this protocol type for dependency injection:
/// ```swift
/// actor MyCoordinator {
///     private let contactService: any ContactServiceProtocol
///
///     init(contactService: any ContactServiceProtocol) {
///         self.contactService = contactService
///     }
/// }
/// ```
protocol ContactServiceProtocol: Actor {
  // MARK: - Contact Sync

  /// Sync all contacts from device
  /// - Parameters:
  ///   - radioID: The device to sync from
  ///   - since: Optional date for incremental sync (only contacts modified after this time)
  /// - Returns: Sync result with count and timestamp
  func syncContacts(radioID: UUID, since: Date?) async throws -> ContactSyncResult
}

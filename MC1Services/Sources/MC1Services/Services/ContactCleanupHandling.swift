import Foundation

/// Cross-service side effects of a contact lifecycle change (block, unblock,
/// delete). `ContactService` invokes this after its own database writes;
/// `ContactCleanupCoordinator` is the production implementation.
protocol ContactCleanupHandling: Sendable {
  /// Runs the cleanup chain for one contact.
  /// - Parameters:
  ///   - contactID: The affected contact's local ID.
  ///   - reason: Which lifecycle change triggered the cleanup.
  ///   - publicKey: The contact's public key, used to locate any associated
  ///     remote node session.
  func handleCleanup(contactID: UUID, reason: ContactCleanupReason, publicKey: Data) async
}

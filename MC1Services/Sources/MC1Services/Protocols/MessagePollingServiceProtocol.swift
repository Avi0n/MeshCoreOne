import Foundation

/// Protocol for MessagePollingService to enable testability of SyncCoordinator.
///
/// This protocol abstracts the message polling operations used by SyncCoordinator,
/// allowing it to be tested with mock implementations.
///
/// ## Usage
///
/// Services can accept this protocol type for dependency injection:
/// ```swift
/// actor MyCoordinator {
///     private let messagePollingService: any MessagePollingServiceProtocol
///
///     init(messagePollingService: any MessagePollingServiceProtocol) {
///         self.messagePollingService = messagePollingService
///     }
/// }
/// ```
public protocol MessagePollingServiceProtocol: Actor {

    // MARK: - Message Polling

    /// Poll all waiting messages from the device.
    /// - Returns: Count of messages retrieved
    func pollAllMessages() async throws -> Int

    /// Wait for all pending message handlers to complete.
    /// Call this after pollAllMessages() to ensure all messages are fully processed.
    func waitForPendingHandlers(timeout: Duration) async -> Bool
}

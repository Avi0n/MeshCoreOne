import Foundation
import MeshCore

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
protocol MessagePollingServiceProtocol: Actor {

    // MARK: - Message Polling

    /// Poll all waiting messages from the device.
    /// - Returns: Count of messages retrieved
    func pollAllMessages() async throws -> Int

    /// Wait for all pending message handlers to complete.
    /// Call this after pollAllMessages() to ensure all messages are fully processed.
    func waitForPendingHandlers(timeout: Duration) async -> Bool

    // MARK: - Auto-Fetch Lifecycle

    /// Start periodic message auto-fetch for the connected radio.
    func startAutoFetch(radioID: UUID) async

    /// Pause auto-fetch (e.g. while a sync owns the transport).
    func pauseAutoFetch() async

    /// Resume auto-fetch after a pause.
    func resumeAutoFetch() async

    // MARK: - Ingestion Handlers

    /// Install the handler invoked for each incoming direct message.
    func setContactMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?, DeliveryContext) async -> Void)

    /// Install the handler invoked for each incoming channel message.
    func setChannelMessageHandler(_ handler: @escaping @Sendable (ChannelMessage, ChannelDTO?, DeliveryContext) async -> Void)

    /// Install the handler invoked for each incoming signed room message.
    func setSignedMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void)

    /// Install the handler invoked for each incoming CLI response.
    func setCLIMessageHandler(_ handler: @escaping @Sendable (ContactMessage, ContactDTO?) async -> Void)
}

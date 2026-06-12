import Foundation

/// Session operations for draining the device's message queue.
public protocol MessageFetchSessionOps: Actor {

    /// Returns one message at a time from the device's message queue. Call repeatedly
    /// until ``MessageResult/noMoreMessages`` is returned to drain the queue.
    ///
    /// - Parameter timeout: Optional timeout override in seconds. Uses the session's default timeout when `nil`.
    /// - Returns: A ``MessageResult`` containing the fetched message, if any.
    /// - Throws: `MeshCoreError` if the fetch fails.
    func getMessage(timeout: TimeInterval?) async throws -> MessageResult

    /// Starts automatic message fetching in response to device notifications.
    func startAutoMessageFetching() async

    /// Stops the automatic fetching started by ``startAutoMessageFetching()``.
    func stopAutoMessageFetching()
}

// MARK: - Default Implementations

public extension MessageFetchSessionOps {
    /// Fetches one message using the session's default timeout.
    func getMessage() async throws -> MessageResult {
        try await getMessage(timeout: nil)
    }
}

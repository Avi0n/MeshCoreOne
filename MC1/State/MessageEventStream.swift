import Foundation
import MC1Services

/// Distributes already-resolved MC1 `MessageEvent` values to chat and room
/// consumers.
///
/// Owned by `AppState`. Service callbacks wired in
/// `AppState.wireMessageEvents` feed events into this stream. Consumers
/// obtain a fresh `AsyncStream` from `events()` and consume it from a
/// SwiftUI `.task` block — lifecycle is bound to the view, so cancellation
/// propagates automatically on view disappear.
///
/// The stream type is `MessageEvent` (MC1-local, contact-resolved DTOs), not
/// `MeshEvent` (the firmware-wire enum). Routing the wire enum into chat
/// consumers would force duplicating `SyncCoordinator`'s resolution.
@MainActor
final class MessageEventStream {
    private var continuations: [UUID: AsyncStream<MessageEvent>.Continuation] = [:]

    /// Vends an `AsyncStream` for a single consumer. Each call yields an
    /// independent subscription keyed by a fresh `UUID`; multiple `.task`
    /// blocks can subscribe to the same stream and each receives every event.
    func events() -> AsyncStream<MessageEvent> {
        let id = UUID()
        return AsyncStream { continuation in
            self.continuations[id] = continuation
            continuation.onTermination = { [weak self, id] _ in
                // `onTermination` is `@Sendable` and Apple's docs note it may
                // run on any thread. `MainActor.assumeIsolated` would trap
                // off-main, so hop via a Task. The window between the iterator
                // tearing down and this Task landing is closed opportunistically
                // by `send(_:)` below — any in-flight send that finds the slot
                // terminated removes it synchronously on the main actor.
                Task { @MainActor in
                    self?.continuations.removeValue(forKey: id)
                }
            }
        }
    }

    /// Broadcasts an event to every active subscriber.
    ///
    /// Two-pass to avoid mutating `continuations` while iterating: collect
    /// stale ids whose `yield` returned `.terminated` (the slot's iterator has
    /// already been cancelled), then prune them after the loop.
    func send(_ event: MessageEvent) {
        var staleIDs: [UUID] = []
        for (id, continuation) in continuations {
            if case .terminated = continuation.yield(event) {
                staleIDs.append(id)
            }
        }
        for id in staleIDs {
            continuations.removeValue(forKey: id)
        }
    }

    #if DEBUG
    /// Test-only accessor for asserting cleanup behaviour from unit tests.
    func subscriberCount() -> Int {
        continuations.count
    }
    #endif
}

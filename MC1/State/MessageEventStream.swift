import Foundation
import MC1Services

/// Distributes already-resolved MC1 `MessageEvent` values to chat and room
/// consumers.
///
/// Owned by `AppState`. `MessageEventDispatcher`'s stream-consuming tasks
/// feed events into this stream. Consumers
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

    func events() -> AsyncStream<MessageEvent> {
        let id = UUID()
        return AsyncStream { continuation in
            self.continuations[id] = continuation
            continuation.onTermination = { [weak self, id] _ in
                Task { @MainActor in
                    self?.continuations.removeValue(forKey: id)
                }
            }
        }
    }

    func send(_ event: MessageEvent) {
        var staleIDs: [UUID] = []
        for (id, continuation) in continuations {
            if case .terminated = continuation.yield(event) {
                staleIDs.append(id)
            }
        }
        for id in staleIDs { continuations.removeValue(forKey: id) }
    }

    #if DEBUG
    func subscriberCount() -> Int {
        continuations.count
    }
    #endif
}

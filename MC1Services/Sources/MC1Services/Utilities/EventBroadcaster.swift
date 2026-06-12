import Foundation
import os

/// Multicast broadcaster delivering typed events to any number of `AsyncStream` subscribers.
///
/// Contract:
/// - Multicast: every subscriber receives every event yielded after it subscribes.
///   Each `subscribe()` call returns a fresh stream, so coexisting consumers
///   (two view models in iPad split view) never steal each other's events.
/// - Synchronous registration: the continuation is installed before `subscribe()`
///   returns, so an event yielded immediately afterward is never dropped behind
///   a registration hop.
/// - `yield(_:)` is synchronous from any isolation, preserving per-producer
///   event ordering.
/// - `finish()` ends every subscriber's for-await loop; the owning container
///   calls it on teardown so consumer tasks release their service references.
///   Subscribing again after `finish()` starts a fresh subscription.
final class EventBroadcaster<Event: Sendable>: Sendable {

    private let continuations = OSAllocatedUnfairLock<[UUID: AsyncStream<Event>.Continuation]>(initialState: [:])

    init() {}

    /// Returns a fresh stream receiving every event yielded after this call.
    /// Cancelling the consuming task unregisters the subscriber.
    func subscribe() -> AsyncStream<Event> {
        let (stream, continuation) = AsyncStream.makeStream(of: Event.self)
        let id = UUID()
        continuations.withLock { $0[id] = continuation }
        continuation.onTermination = { [continuations] _ in
            continuations.withLock { _ = $0.removeValue(forKey: id) }
        }
        return stream
    }

    /// Delivers the event to every active subscriber, pruning any that
    /// terminated without unregistering.
    func yield(_ event: Event) {
        let snapshot = continuations.withLock { $0 }
        var staleIDs: [UUID] = []
        for (id, continuation) in snapshot {
            if case .terminated = continuation.yield(event) {
                staleIDs.append(id)
            }
        }
        let staleToPrune = staleIDs
        guard !staleToPrune.isEmpty else { return }
        continuations.withLock { state in
            for id in staleToPrune {
                state.removeValue(forKey: id)
            }
        }
    }

    /// Ends every subscriber's stream and unregisters them all.
    func finish() {
        let snapshot = continuations.withLock { state in
            let current = state
            state.removeAll()
            return current
        }
        for continuation in snapshot.values {
            continuation.finish()
        }
    }

    /// Number of currently registered subscribers.
    var subscriberCount: Int {
        continuations.withLock { $0.count }
    }
}

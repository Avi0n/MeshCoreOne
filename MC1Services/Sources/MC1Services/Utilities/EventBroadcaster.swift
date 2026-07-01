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
///   Calling `subscribe()` after `finish()` returns a stream that is already
///   finished, so its for-await loop exits immediately rather than parking.
final class EventBroadcaster<Event: Sendable>: Sendable {
  private struct State {
    var continuations: [UUID: AsyncStream<Event>.Continuation] = [:]
    var isFinished: Bool = false
  }

  private let state = OSAllocatedUnfairLock<State>(initialState: State())

  init() {}

  /// Returns a stream receiving every event yielded after this call.
  /// If `finish()` has already been called, returns a stream that finishes
  /// immediately. Cancelling the consuming task unregisters the subscriber.
  func subscribe() -> AsyncStream<Event> {
    let (stream, continuation) = AsyncStream.makeStream(of: Event.self)
    let alreadyFinished = state.withLock { locked -> Bool in
      guard !locked.isFinished else { return true }
      let id = UUID()
      locked.continuations[id] = continuation
      continuation.onTermination = { [state] _ in
        state.withLock { _ = $0.continuations.removeValue(forKey: id) }
      }
      return false
    }
    if alreadyFinished {
      continuation.finish()
    }
    return stream
  }

  /// Delivers the event to every active subscriber, pruning any that
  /// terminated without unregistering.
  func yield(_ event: Event) {
    let snapshot = state.withLock { $0.continuations }
    var staleIDs: [UUID] = []
    for (id, continuation) in snapshot {
      if case .terminated = continuation.yield(event) {
        staleIDs.append(id)
      }
    }
    let staleToPrune = staleIDs
    guard !staleToPrune.isEmpty else { return }
    state.withLock { locked in
      for id in staleToPrune {
        locked.continuations.removeValue(forKey: id)
      }
    }
  }

  /// Ends every subscriber's stream and unregisters them all.
  /// Any subsequent `subscribe()` call returns an already-finished stream.
  func finish() {
    let snapshot = state.withLock { locked -> [UUID: AsyncStream<Event>.Continuation] in
      let current = locked.continuations
      locked.continuations.removeAll()
      locked.isFinished = true
      return current
    }
    for continuation in snapshot.values {
      continuation.finish()
    }
  }

  /// Number of currently registered subscribers.
  var subscriberCount: Int {
    state.withLock { $0.continuations.count }
  }
}

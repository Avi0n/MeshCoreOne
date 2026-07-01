import Foundation

/// Session operations for observing connection state and subscribing to device events.
public protocol SessionEventStreaming: Actor {
  // MARK: - Connection State

  /// Provides an observable connection state stream for UI binding.
  var connectionState: AsyncStream<ConnectionState> { get }

  // MARK: - Event Operations

  /// Subscribes to all events from the device.
  ///
  /// Each subscriber receives all events independently.
  ///
  /// - Returns: An async stream yielding ``MeshEvent`` values as they are received.
  func events() async -> AsyncStream<MeshEvent>

  /// Subscribes to events passing the given filter.
  ///
  /// Prefer this over ``events()`` when the consumer only cares about a
  /// narrow slice of events.
  ///
  /// - Parameter filter: The ``EventFilter`` that determines which events reach the stream.
  /// - Returns: An async stream yielding only events that pass `filter`.
  func events(filter: EventFilter) async -> AsyncStream<MeshEvent>

  /// Waits for an event matching an ``EventFilter`` with timeout.
  ///
  /// - Parameters:
  ///   - filter: The event filter to apply.
  ///   - timeout: Maximum time to wait in seconds. Uses the session's default timeout when `nil`.
  /// - Returns: The matching event, or `nil` if the timeout expired.
  func waitForEvent(filter: EventFilter, timeout: TimeInterval?) async -> MeshEvent?
}

// MARK: - Default Implementations

public extension SessionEventStreaming {
  /// Waits for an event using the session's default timeout.
  func waitForEvent(filter: EventFilter) async -> MeshEvent? {
    await waitForEvent(filter: filter, timeout: nil)
  }
}

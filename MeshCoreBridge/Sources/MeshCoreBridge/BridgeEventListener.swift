import Foundation

/// Receives push events from the device.
///
/// One internal bridge task consumes the session's event stream and forwards
/// every `MeshEvent` here as a versioned JSON envelope (see `ENCODING.md`). This
/// includes connection-state transitions, which arrive as a
/// `connectionStateChanged` event. Methods may be invoked on any thread.
public protocol BridgeEventListener: AnyObject, Sendable {
  /// A device event, encoded as a versioned JSON envelope.
  func onEvent(eventBytes: [UInt8])
}

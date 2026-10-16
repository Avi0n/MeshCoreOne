import Foundation

/// Represents the current connection state of a MeshCore session.
///
/// Use this enum to update your UI based on connection status. Subscribe to
/// state changes via ``MeshCoreSession/connectionState``.
///
/// ## Example
///
/// ```swift
/// for await state in session.connectionState {
///     switch state {
///     case .connected:
///         showConnectedUI()
///     case .connecting:
///         showLoadingIndicator()
///     case .reconnecting(let attempt):
///         showReconnecting(attempt: attempt)
///     case .failed(let error):
///         showError(error)
///     case .disconnected:
///         showDisconnectedUI()
///     }
/// }
/// ```
public enum ConnectionState: Sendable, Equatable {
  /// Indicates the session is disconnected.
  case disconnected
  /// Indicates the session is attempting to connect.
  case connecting
  /// Indicates the session is successfully connected.
  case connected
  /// Indicates the session is attempting to reconnect after a failure.
  case reconnecting(attempt: Int)
  /// Indicates the session connection failed with a specific error.
  case failed(MeshTransportError)
}

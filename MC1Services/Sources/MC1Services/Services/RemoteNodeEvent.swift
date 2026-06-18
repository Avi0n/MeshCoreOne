import Foundation

/// Remote-node session notifications broadcast by `RemoteNodeService.events()`.
/// The stream is multicast: every subscriber receives every event.
public enum RemoteNodeEvent: Sendable {
    /// A remote-node session's connection state changed (login, logout,
    /// keep-alive failure, or BLE loss).
    case sessionStateChanged(sessionID: UUID, isConnected: Bool)
}

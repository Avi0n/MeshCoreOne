/// App-facing connection state for the mesh device.
///
/// Adds the post-link `syncing`/`ready` distinction the transport layer has no
/// concept of. Distinct from `MeshCore.ConnectionState`, which is the lower
/// transport-link state the session publishes; `ConnectionManager` translates
/// transport events into the rung modeled here.
public enum DeviceConnectionState: Sendable {
    case disconnected
    case connecting
    case connected
    case syncing
    case ready

    /// True when session and services are available and the transport is alive.
    /// Used by internal infrastructure (resync loop, health checks, heartbeat).
    /// UI code should check `== .ready` to gate user interactions.
    public var isOperational: Bool {
        self == .syncing || self == .ready
    }

    /// True when a transport link is established (session may or may not be synced).
    public var isConnected: Bool {
        switch self {
        case .connected, .syncing, .ready: true
        case .disconnected, .connecting: false
        }
    }

    /// True only once initial sync has cleared, so the chat send queue may drain without
    /// contending with sync's contact/channel/message reads on the radio's link. `.connected`
    /// (link up, sync not yet run) deliberately excludes the queue from the vulnerable window.
    public var canDrainSendQueue: Bool {
        self == .ready
    }
}

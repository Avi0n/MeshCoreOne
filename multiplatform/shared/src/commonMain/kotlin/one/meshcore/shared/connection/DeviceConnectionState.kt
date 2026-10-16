package one.meshcore.shared.connection

/**
 * The app-facing connection rung the status pill and settings-gating logic read, ported from the iOS
 * `DeviceConnectionState`. It adds the post-link [SYNCING]/[READY] distinction that the transport layer
 * lacks.
 *
 * This is the coarser sibling of the transport-level [ConnectionState] sealed interface: [ConnectionState]
 * additionally models the auto-reconnect cycle and a lost bond, which collapse here to [CONNECTING] and
 * [DISCONNECTED] respectively via [ConnectionState.toDeviceConnectionState].
 */
enum class DeviceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SYNCING,
    READY,
    ;

    /** Synced enough to be usable: syncing or ready. */
    val isOperational: Boolean
        get() = this == SYNCING || this == READY

    /** A transport link is up: connected, syncing, or ready. */
    val isConnected: Boolean
        get() = this == CONNECTED || this == SYNCING || this == READY

    /**
     * A radio session is live or in progress, including the connecting rung. Backup import must
     * refuse this so restore cannot race a reconnect that landed while the picker was open.
     */
    val isRadioLinked: Boolean
        get() = this != DISCONNECTED

    /**
     * Whether the outbound send queue may drain. Only when [READY]: draining before initial sync
     * completes would contend with sync reads on the radio link.
     */
    val canDrainSendQueue: Boolean
        get() = this == READY

    /**
     * Whether an open channel may push the session flood key. iOS does this at Connected (before
     * Ready) so inherit-scope traffic is keyed before the send queue drains.
     */
    val canPushChannelFloodScope: Boolean
        get() = isConnected

    /**
     * Whether an open thread should fold ingest and status events. iOS collects
     * `messageEventStream` for the screen lifetime; the dispatcher is wired as soon as
     * `ServiceContainer` exists (Connected), not only Ready. Gating on Ready drops inbound
     * bubbles and ACK crossfades that land during sync.
     */
    val canCollectThreadEvents: Boolean
        get() = isConnected
}

/**
 * Projects the richer transport state onto the app-facing rung. An in-flight auto-reconnect reads as
 * [DeviceConnectionState.CONNECTING]; a lost bond reads as [DeviceConnectionState.DISCONNECTED] (the pill
 * treats it as disconnected; the distinct recovery UX is driven from [ConnectionState] directly).
 */
fun ConnectionState.toDeviceConnectionState(): DeviceConnectionState = when (this) {
    is ConnectionState.Disconnected -> DeviceConnectionState.DISCONNECTED
    is ConnectionState.Connecting -> DeviceConnectionState.CONNECTING
    is ConnectionState.Reconnecting -> DeviceConnectionState.CONNECTING
    is ConnectionState.Connected -> DeviceConnectionState.CONNECTED
    is ConnectionState.Syncing -> DeviceConnectionState.SYNCING
    is ConnectionState.Ready -> DeviceConnectionState.READY
    is ConnectionState.BondLost -> DeviceConnectionState.DISCONNECTED
}

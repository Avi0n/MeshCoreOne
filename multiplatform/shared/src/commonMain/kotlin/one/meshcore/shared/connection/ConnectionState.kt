package one.meshcore.shared.connection

/**
 * App-facing connection state for a mesh radio, driven by [ConnectionManager].
 *
 * This mirrors the union of the two Swift state layers the manager cares about: the
 * `DeviceConnectionState` rungs (`disconnected`, `connecting`, `connected`, `syncing`, `ready`) plus
 * the `BLEStateMachine`/`BLEReconnectionCoordinator` distinctions the rungs cannot express —
 * [Reconnecting] for an in-flight auto-reconnect cycle (Swift `.autoReconnecting`), and [BondLost] for
 * a silently invalidated bond (Swift `BLEError.authenticationFailed`), which must surface distinctly
 * because it cannot heal through the ordinary reconnect/backoff loop.
 *
 * `deviceId` is the transport's volatile connection handle (a Kable peripheral identifier), used only
 * for correlation and logging. It is deliberately not an identity: per the three-axis invariant the
 * data-partition key is `radioID`, minted once and never derived from this handle.
 */
sealed interface ConnectionState {
    /** The transport handle for this state, or `null` when [Disconnected]. */
    val deviceId: String?

    /** No device connected; the terminal resting state and the only state a user disconnect reaches. */
    data object Disconnected : ConnectionState {
        override val deviceId: String? get() = null
    }

    /** The transport link is establishing (scan, connect, service discovery, MTU, notifications). */
    data class Connecting(override val deviceId: String) : ConnectionState

    /** The link is up and the session handshake completed, but initial sync has not yet run. */
    data class Connected(override val deviceId: String) : ConnectionState

    /** Initial sync (contacts, and later channels/messages) is running over a live link. */
    data class Syncing(override val deviceId: String) : ConnectionState

    /** Synced and operational: the rung UI gates interactions on. */
    data class Ready(override val deviceId: String) : ConnectionState

    /**
     * An auto-reconnect cycle is in flight after an unexpected drop. `attempt` counts backoff
     * iterations so the UI (and tests) can distinguish the first retry from a long-running one.
     */
    data class Reconnecting(override val deviceId: String, val attempt: Int) : ConnectionState

    /**
     * The peer's bond was invalidated (radios spontaneously drop bonds in the field). Recovery needs
     * a guided re-pair, so this is surfaced distinctly rather than folded into the reconnect loop,
     * which would silently retry a bond that cannot heal itself.
     */
    data class BondLost(override val deviceId: String) : ConnectionState

    /** True once a transport link is established (session may or may not be synced). */
    val isLinkUp: Boolean
        get() = this is Connected || this is Syncing || this is Ready

    /**
     * True when a radio session is live or in progress. Backup import must refuse this so restore
     * cannot race a reconnect that landed while the picker was open.
     */
    val isConnected: Boolean
        get() = this !is Disconnected && this !is BondLost
}

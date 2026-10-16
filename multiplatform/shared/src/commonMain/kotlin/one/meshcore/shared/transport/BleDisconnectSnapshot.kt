package one.meshcore.shared.transport

/** Fields on the iOS `handleDidDisconnect` dump line. */
internal data class BleDisconnectSnapshot(
    val handle: String,
    val peripheralState: String,
    val isReconnecting: Boolean,
    val phase: String,
    val elapsedSeconds: String,
    val error: String,
)

package one.meshcore.shared.connection

/** Shared fields on the disconnecting and disconnected dump lines. */
internal data class ConnectionDropContext(
    val reason: String,
    val transport: String,
    val device: String,
    val initialState: String,
    val intent: String,
)

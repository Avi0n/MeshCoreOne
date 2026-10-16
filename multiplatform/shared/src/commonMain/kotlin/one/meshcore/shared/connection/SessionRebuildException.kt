package one.meshcore.shared.connection

/**
 * Handshake or `queryDevice` failed after the physical link was already up. The reconnect loop
 * rebuilds the session over that GATT instead of cancelling a standing `autoConnect`.
 */
class SessionRebuildException(cause: Throwable) : Exception(cause.message, cause)

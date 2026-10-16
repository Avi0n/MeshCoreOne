package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException

/**
 * Closes a link that has already dropped. A thrown failure is logged. The handshake
 * cancellation still leaves this function when disconnect itself is cancelled.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun disconnectDeadLink(
    warning: (String) -> Unit,
    handshakeCancelled: CancellationException,
    disconnect: suspend () -> Unit,
): Nothing {
    try {
        disconnect()
    } catch (_: CancellationException) {
        throw handshakeCancelled
    } catch (failure: Throwable) {
        warning("$LINK_DISCONNECT_FAILED: ${failure.message ?: failure}")
    }
    throw handshakeCancelled
}

internal const val LINK_DISCONNECT_FAILED = "Link disconnect failed"

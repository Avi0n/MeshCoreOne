package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException

/**
 * Runs one TCP link operation. [onCancel] closes a socket that connect already opened, then rethrows.
 * Any other failure, including an [Error], is handed to [onFailure] so connect can surface
 * [TcpTransportException.ConnectionFailed] and a dead reader or writer can drop the link.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runTcpLink(
    onFailure: suspend (Throwable) -> T,
    onCancel: (CancellationException) -> Nothing = { throw it },
    block: suspend () -> T,
): T = try {
    block()
} catch (cancelled: CancellationException) {
    onCancel(cancelled)
} catch (error: Exception) {
    onFailure(error)
} catch (error: Error) {
    onFailure(error)
}

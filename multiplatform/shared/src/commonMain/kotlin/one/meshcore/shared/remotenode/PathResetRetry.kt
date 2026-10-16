package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.settings.PersistentLogger

/**
 * Runs [operation] once; on a mesh timeout, resets the destination path to flood and retries once.
 * Mirrors iOS `BinaryProtocolService.performWithPathResetOnTimeout`. A failed reset rethrows the
 * original timeout so a dead link is not retried on the same stale path.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun <T> performWithPathResetOnTimeout(
    publicKey: ByteArray,
    resetPath: suspend (ByteArray) -> Unit,
    operation: suspend () -> T,
    operationName: String,
    logger: PersistentLogger? = null,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    if (!isMeshTimeout(error)) throw error
    logger?.info("$operationName: mesh timeout; resetting path to flood and retrying once")
    resetFloodPath(publicKey, resetPath, error, operationName, logger)
    operation()
}

/** A failed reset, including an [Error], keeps the original timeout. Cancellation still stops the retry. */
@Suppress("TooGenericExceptionCaught")
private suspend fun resetFloodPath(
    publicKey: ByteArray,
    resetPath: suspend (ByteArray) -> Unit,
    original: Throwable,
    operationName: String,
    logger: PersistentLogger?,
) {
    try {
        resetPath(publicKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (resetError: Throwable) {
        logger?.warning(
            "$operationName: path reset failed (${resetError.message ?: resetError}); not retrying",
        )
        throw original
    }
}

internal fun isMeshTimeout(error: Throwable): Boolean = error is MeshError.Timeout ||
    error is RemoteNodeError.Timeout ||
    (error is MeshBridgeException && error.code == BridgeErrorCode.TIMEOUT)

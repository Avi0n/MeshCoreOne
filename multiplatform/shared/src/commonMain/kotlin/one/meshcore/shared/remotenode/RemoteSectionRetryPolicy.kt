package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import one.meshcore.shared.meshcore.MeshBridgeException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The transient-retry ladder shared by the remote-node status, telemetry, owner-info, and neighbour section
 * requests, mirroring the Swift `NodeStatusViewModel.performWithTransientRetries`.
 *
 * Firmware answers a multi-hop binary request with `remoteNodeNoResponseYet` while the reply is still in
 * flight. That is not a failure, so the request is re-issued at [TRANSIENT_RETRY_DELAYS] spacing inside one
 * shared [RemoteOperationTimeoutPolicy.BINARY_MAXIMUM] budget; each attempt gets only the budget that
 * remains. Any other error, or an exhausted ladder, propagates and the section shows its error — the
 * section timeout stays terminal.
 */
object RemoteSectionRetryPolicy {
    /**
     * Firmware `remoteNodeNoResponseYet`: the remote node has not answered this binary request yet. MC1
     * reads it as "still in flight", not as a failure. Mirrors the Swift `FirmwareDeviceErrorCode`.
     */
    const val REMOTE_NODE_NO_RESPONSE_YET: Int = 10

    /** Spacing between re-issues; the ladder length also caps the number of retries at three. */
    val TRANSIENT_RETRY_DELAYS: List<Duration> = listOf(500.milliseconds, 1.seconds, 2.seconds)

    /** True when [error] is a firmware "no response yet" report, the only error this ladder retries. */
    fun isTransientError(error: Throwable): Boolean {
        val mesh = when (error) {
            is RemoteNodeError.SessionError -> error.error
            is MeshError -> error
            is MeshBridgeException -> error.toMeshError()
            else -> return false
        }
        return mesh is MeshError.DeviceError && mesh.code == REMOTE_NODE_NO_RESPONSE_YET
    }

    /**
     * Runs [operation], passing it the budget still left, and re-issues it on a transient firmware reply
     * until the ladder or [budget] runs out. Throws [RemoteNodeError.Timeout] when the budget is gone and
     * rethrows any non-transient error unchanged.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> performWithTransientRetries(
        budget: Duration = RemoteOperationTimeoutPolicy.BINARY_MAXIMUM,
        timeSource: TimeSource = TimeSource.Monotonic,
        operation: suspend (timeout: Duration) -> T,
    ): T {
        val start = timeSource.markNow()
        var nextDelayIndex = 0

        while (true) {
            val remaining = budget - start.elapsedNow()
            throwIfRetryBudgetSpent(remaining)
            val produced = try {
                Result.success(operation(remaining))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            produced.fold(
                onSuccess = { return it },
                onFailure = { error ->
                    if (!isTransientError(error) || nextDelayIndex >= TRANSIENT_RETRY_DELAYS.size) throw error
                    val nextDelay = TRANSIENT_RETRY_DELAYS[nextDelayIndex]
                    nextDelayIndex += 1
                    val left = budget - start.elapsedNow()
                    throwIfRetryBudgetSpent(left)
                    delay(minOf(nextDelay, left))
                },
            )
        }
    }

    private fun throwIfRetryBudgetSpent(remaining: Duration) {
        if (remaining <= Duration.ZERO) throw RemoteNodeError.Timeout
    }
}

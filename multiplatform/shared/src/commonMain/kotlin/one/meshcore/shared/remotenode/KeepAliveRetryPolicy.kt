package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException

/**
 * Keep-alive retry decisions, mirroring the Swift `KeepAliveRetryPolicy`. Classifies a failure into one
 * of five actions:
 * - **Transient** (timeout, device error, not connected): retried up to [MAX_CONSECUTIVE_FAILURES] times.
 * - **Terminal** (session/contact not found, unknown errors): disconnect immediately.
 * - **Skip** (flood-routed): not a failure, continue the loop.
 * - **Stop** (cancellation): the loop was cancelled, exit quietly.
 */
object KeepAliveRetryPolicy {
    /** Consecutive transient failures required before disconnecting. */
    const val MAX_CONSECUTIVE_FAILURES: Int = 2

    enum class Action {
        /** Transient failure, try again next interval. */
        RETRY_NEXT_INTERVAL,

        /** Consecutive transient failures exceeded the threshold. */
        DISCONNECT,

        /** Terminal local-state error, disconnect immediately. */
        DISCONNECT_NOW,

        /** Flood-routed session, skip this iteration. */
        SKIP,

        /** Loop cancelled, exit quietly. */
        STOP,
        ;

        val shouldExitLoop: Boolean
            get() = when (this) {
                STOP, DISCONNECT, DISCONNECT_NOW -> true
                RETRY_NEXT_INTERVAL, SKIP -> false
            }
    }

    /** The classified [action] plus the updated [consecutiveFailures] counter. */
    data class Evaluation(val action: Action, val consecutiveFailures: Int)

    /**
     * Classifies a keep-alive [error] given the running [consecutiveFailures] count, returning the action
     * and the updated count. A non-[RemoteNodeError] (e.g. a persistence failure) is terminal.
     */
    fun evaluate(error: Throwable, consecutiveFailures: Int): Evaluation {
        if (error is CancellationException) return Evaluation(Action.STOP, consecutiveFailures)

        val nodeError = error as? RemoteNodeError ?: return Evaluation(Action.DISCONNECT_NOW, consecutiveFailures)

        return when (nodeError) {
            RemoteNodeError.Cancelled -> Evaluation(Action.STOP, consecutiveFailures)
            RemoteNodeError.FloodRouted -> Evaluation(Action.SKIP, consecutiveFailures)
            RemoteNodeError.SessionNotFound, RemoteNodeError.ContactNotFound ->
                Evaluation(Action.DISCONNECT_NOW, consecutiveFailures)

            else -> {
                val next = consecutiveFailures + 1
                val action = if (next >= MAX_CONSECUTIVE_FAILURES) Action.DISCONNECT else Action.RETRY_NEXT_INTERVAL
                Evaluation(action, next)
            }
        }
    }

    /** A human-readable reason for a keep-alive failure, for logging. */
    fun failureReason(error: Throwable): String = when (error) {
        is RemoteNodeError.SessionError -> when (val cause = error.error) {
            MeshError.Timeout -> "timeout"
            is MeshError.DeviceError -> "device error (code: ${cause.code})"
            MeshError.NotConnected -> "transport not connected"
            else -> cause.message ?: cause.toString()
        }
        RemoteNodeError.SessionNotFound -> "session not found"
        RemoteNodeError.ContactNotFound -> "contact not found"
        else -> error.message ?: error.toString()
    }
}

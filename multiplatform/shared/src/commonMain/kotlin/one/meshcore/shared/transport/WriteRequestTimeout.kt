package one.meshcore.shared.transport

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The iOS `claimWriteSlot` 5s backstop for an ATT Write Request. A wedged controller must not park
 * the single BLE writer: [write] returns `false` on timeout so the caller can fail the live link
 * instead of treating the frame as transmitted.
 */
object WriteRequestTimeout {
    val TIMEOUT: Duration = 5.seconds

    suspend fun write(
        timeout: Duration = TIMEOUT,
        onTimeout: (() -> Unit)? = null,
        attempt: suspend () -> Unit,
    ): Boolean {
        val completed = withTimeoutOrNull(timeout) {
            attempt()
            true
        } != null
        if (!completed) onTimeout?.invoke()
        return completed
    }
}

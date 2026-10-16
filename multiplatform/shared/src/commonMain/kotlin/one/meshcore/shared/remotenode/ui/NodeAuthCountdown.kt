package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/** Clears the in-flight, countdown, and retry flags, leaving any error to the caller. */
internal fun NodeAuthState.settledAfterCountdown(): NodeAuthState = copy(
    isAuthenticating = false,
    secondsRemaining = null,
    secondsRemainingAnnouncement = null,
    isRetryingViaFlood = false,
)

/** Firmware-timeout countdown for the authentication sheet. Ticks follow wall time, not a drifting counter. */
internal class NodeAuthCountdown(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val state: MutableStateFlow<NodeAuthState>,
) {
    private var countdownJob: Job? = null

    internal fun start(seconds: Int) {
        cancel()
        val startMs = nowMillis()
        state.update { it.copy(secondsRemaining = seconds, secondsRemainingAnnouncement = seconds) }
        countdownJob = scope.launch {
            while (true) {
                delay(COUNTDOWN_TICK_MS)
                // Recompute from elapsed wall time rather than decrementing a counter, so a late tick shows the
                // true remaining seconds instead of drifting one behind for every tick that ran long.
                val elapsedSeconds = ((nowMillis() - startMs) / MILLIS_PER_SECOND).toInt()
                val remaining = max(0, seconds - elapsedSeconds)
                applyTick(remaining)
                if (remaining <= 0) break
            }
        }
    }

    internal fun cancel() {
        countdownJob?.cancel()
        countdownJob = null
    }

    private fun applyTick(remaining: Int) {
        state.update { current ->
            val announcement =
                if (remaining > 0 && NodeAuthViewModel.shouldAnnounceCountdown(current.secondsRemaining, remaining)) {
                    remaining
                } else {
                    current.secondsRemainingAnnouncement
                }
            current.copy(secondsRemaining = remaining, secondsRemainingAnnouncement = announcement)
        }
    }

    private companion object {
        const val COUNTDOWN_TICK_MS = 1_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

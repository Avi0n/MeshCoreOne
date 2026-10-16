package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Epoch seconds that advance once a minute, for relative labels like "5m" and "2h". The analogue of the
 * iOS `TimelineView(.everyMinute)` the conversation and node lists render inside.
 *
 * A list that anchors its clock once at composition freezes every relative timestamp for as long as the
 * screen lives, so a row saying "5m" still says "5m" hours later — and a responder reads stale contact as
 * recent. The first tick is aligned to the next whole minute so every row on screen turns over together.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
@Composable
fun rememberMinuteTickSeconds(): State<Long> {
    val now = remember { mutableLongStateOf(Clock.System.now().epochSeconds) }
    LaunchedEffect(Unit) {
        while (true) {
            val current = Clock.System.now().epochSeconds
            now.longValue = current
            delay((SECONDS_PER_MINUTE - current % SECONDS_PER_MINUTE) * MILLIS_PER_SECOND)
        }
    }
    return now
}

private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L

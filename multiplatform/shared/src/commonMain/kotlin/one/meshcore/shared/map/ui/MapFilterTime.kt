package one.meshcore.shared.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.map.MapLastHeardRange

private const val MAP_TIME_WINDOW_REFRESH_MS = 60_000L

/** Refilters cached nodes while visible, with an immediate refresh after returning from the background. */
@Composable
internal fun rememberMapFilterTime(range: MapLastHeardRange, nowEpochSeconds: () -> Long): State<Long> {
    val now = remember { mutableLongStateOf(nowEpochSeconds()) }
    val currentClock by rememberUpdatedState(nowEpochSeconds)
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(range) {
        val refresh = if (range.isActive) {
            now.longValue = currentClock()
            scope.launch {
                while (true) {
                    delay(MAP_TIME_WINDOW_REFRESH_MS)
                    now.longValue = currentClock()
                }
            }
        } else {
            null
        }
        onPauseOrDispose { refresh?.cancel() }
    }
    return now
}

package one.meshcore.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.StaleNodeCleanupPreferences
import java.util.concurrent.atomic.AtomicLong

/** Process-lifetime stale-cleanup settings shown by the Settings hub. */
internal class StaleCleanupSettingsState(preferences: StaleNodeCleanupPreferences, scope: CoroutineScope) {
    private val thresholdGeneration = AtomicLong()
    private val lastRunGeneration = AtomicLong()
    private val thresholdDaysState = MutableStateFlow(0)
    private val lastRunSecondsState = MutableStateFlow(0L)

    val thresholdDays: Int get() = thresholdDaysState.value
    val lastRunSeconds: Long get() = lastRunSecondsState.value

    init {
        val thresholdLoadGeneration = thresholdGeneration.get()
        val lastRunLoadGeneration = lastRunGeneration.get()
        scope.launch {
            val thresholdDays = preferences.thresholdDays()
            val lastRunSeconds = preferences.lastRunSeconds()
            if (thresholdGeneration.get() == thresholdLoadGeneration) thresholdDaysState.value = thresholdDays
            if (lastRunGeneration.get() == lastRunLoadGeneration) lastRunSecondsState.value = lastRunSeconds
        }
    }

    fun setThresholdDays(days: Int) {
        thresholdGeneration.incrementAndGet()
        thresholdDaysState.value = days
    }

    fun setLastRunSeconds(seconds: Long) {
        lastRunGeneration.incrementAndGet()
        lastRunSecondsState.value = seconds
    }
}

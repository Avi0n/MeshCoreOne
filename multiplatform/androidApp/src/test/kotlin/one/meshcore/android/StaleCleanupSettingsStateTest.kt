package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.StaleNodeCleanupPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class StaleCleanupSettingsStateTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thresholdEditWinsWhileUnrelatedLastRunStillLoads() = runTest {
        val preferences = GatedStaleCleanupPreferences(gateThreshold = true)
        val state = StaleCleanupSettingsState(preferences, backgroundScope)
        runCurrent()
        preferences.readStarted.await()

        state.setThresholdDays(30)
        preferences.releaseRead.complete(Unit)
        runCurrent()

        assertEquals(30, state.thresholdDays)
        assertEquals(STORED_LAST_RUN, state.lastRunSeconds)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun lastRunUpdateWinsWhileUnrelatedThresholdStillLoads() = runTest {
        val preferences = GatedStaleCleanupPreferences(gateThreshold = false)
        val state = StaleCleanupSettingsState(preferences, backgroundScope)
        runCurrent()
        preferences.readStarted.await()

        state.setLastRunSeconds(NEW_LAST_RUN)
        preferences.releaseRead.complete(Unit)
        runCurrent()

        assertEquals(STORED_THRESHOLD, state.thresholdDays)
        assertEquals(NEW_LAST_RUN, state.lastRunSeconds)
    }

    private companion object {
        const val STORED_THRESHOLD = 7
        const val STORED_LAST_RUN = 100L
        const val NEW_LAST_RUN = 200L
    }

    private class GatedStaleCleanupPreferences(private val gateThreshold: Boolean) :
        StaleNodeCleanupPreferences {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()

        override suspend fun thresholdDays(): Int {
            if (gateThreshold) awaitRelease()
            return STORED_THRESHOLD
        }

        override suspend fun lastRunSeconds(): Long {
            if (!gateThreshold) awaitRelease()
            return STORED_LAST_RUN
        }

        override suspend fun setLastRunSeconds(seconds: Long) = Unit

        private suspend fun awaitRelease() {
            readStarted.complete(Unit)
            releaseRead.await()
        }
    }
}

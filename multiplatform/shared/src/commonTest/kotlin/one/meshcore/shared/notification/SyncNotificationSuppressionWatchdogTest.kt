package one.meshcore.shared.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SyncNotificationSuppressionWatchdogTest {
    @Test
    fun firesAfterTheTimeoutWhileStillSuppressing() = runTest {
        var suppressing = true
        val logs = mutableListOf<String>()
        val job = launch {
            SyncNotificationSuppressionWatchdog.awaitThenForceClear(
                stillSuppressing = { suppressing },
                clear = { suppressing = false },
                log = { logs += it },
                timeout = 50.milliseconds,
            )
        }
        runCurrent()
        advanceTimeBy(49)
        assertTrue(suppressing)
        assertTrue(logs.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertFalse(suppressing)
        assertEquals(listOf(SyncNotificationSuppressionWatchdog.FIRED_LINE), logs)
        job.join()
    }

    @Test
    fun doesNotFireWhenAlreadyCleared() = runTest {
        var suppressing = true
        val logs = mutableListOf<String>()
        val job = launch {
            SyncNotificationSuppressionWatchdog.awaitThenForceClear(
                stillSuppressing = { suppressing },
                clear = { suppressing = false },
                log = { logs += it },
                timeout = 50.milliseconds,
            )
        }
        runCurrent()
        suppressing = false
        advanceTimeBy(50)
        runCurrent()
        assertTrue(logs.isEmpty())
        job.join()
    }

    @Test
    fun cancelBeforeTimeoutLeavesTheFlag() = runTest {
        var suppressing = true
        val logs = mutableListOf<String>()
        val job = launch {
            SyncNotificationSuppressionWatchdog.awaitThenForceClear(
                stillSuppressing = { suppressing },
                clear = { suppressing = false },
                log = { logs += it },
                timeout = 50.milliseconds,
            )
        }
        runCurrent()
        job.cancel()
        advanceTimeBy(50)
        runCurrent()
        assertTrue(suppressing)
        assertTrue(logs.isEmpty())
    }
}

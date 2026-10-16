package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * iOS `syncDeviceTimeIfNeeded` times getTime and setTime separately at 5s each.
 * A combined 5s budget would skip the write after a slow read.
 */
class ConnectionClockSyncTest {
    @Test
    fun slowGetTimeStillWritesWhenTheClockHasDrifted() = runTest {
        var setTime: Long? = null
        val logs = mutableListOf<String>()
        syncRadioClockIfNeeded(
            ConnectionClockSync(
                getTime = {
                    delay(3_000)
                    PHONE_NOW - 100
                },
                setTime = {
                    delay(3_000)
                    setTime = it
                },
                nowEpochSeconds = { PHONE_NOW.toDouble() },
                commandTimeout = 5.seconds,
                driftToleranceSeconds = 5.0,
                logInfo = { logs += it },
                logWarning = { logs += it },
            ),
        )
        assertEquals(PHONE_NOW, setTime)
        assertEquals(listOf(syncedDeviceTimeLine(100)), logs)
    }

    @Test
    fun inToleranceDoesNotWrite() = runTest {
        var setCalls = 0
        syncRadioClockIfNeeded(
            ConnectionClockSync(
                getTime = { PHONE_NOW + 2 },
                setTime = { setCalls += 1 },
                nowEpochSeconds = { PHONE_NOW.toDouble() },
                commandTimeout = 5.seconds,
                driftToleranceSeconds = 5.0,
                logInfo = {},
                logWarning = {},
            ),
        )
        assertEquals(0, setCalls)
    }

    @Test
    fun getTimeTimeoutDoesNotCallSetTime() = runTest {
        var setCalls = 0
        val warnings = mutableListOf<String>()
        syncRadioClockIfNeeded(
            ConnectionClockSync(
                getTime = {
                    delay(6_000)
                    PHONE_NOW
                },
                setTime = { setCalls += 1 },
                nowEpochSeconds = { PHONE_NOW.toDouble() },
                commandTimeout = 5.seconds,
                driftToleranceSeconds = 5.0,
                logInfo = {},
                logWarning = { warnings += it },
            ),
        )
        assertEquals(0, setCalls)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().startsWith(FAILED_SYNC_DEVICE_TIME))
    }

    @Test
    fun aCancelledReadPropagatesWithoutAWarning() = runTest {
        val cancelled = CancellationException("stop")
        var setCalls = 0
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            syncRadioClockIfNeeded(
                ConnectionClockSync(
                    getTime = { throw cancelled },
                    setTime = { setCalls += 1 },
                    nowEpochSeconds = { PHONE_NOW.toDouble() },
                    commandTimeout = 5.seconds,
                    driftToleranceSeconds = 5.0,
                    logInfo = {},
                    logWarning = { warnings += it },
                ),
            )
        }
        // withTimeout copies a cancellation that starts inside the command, so identity is not stable.
        assertTrue(thrown !is TimeoutCancellationException)
        assertEquals("stop", thrown.message)
        assertEquals(0, setCalls)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun callersDeadlineDoesNotBecomeAClockCommandTimeout() = runTest {
        val warnings = mutableListOf<String>()
        withTimeoutOrNull(1.seconds) {
            syncRadioClockIfNeeded(
                ConnectionClockSync(
                    getTime = {
                        delay(2.seconds)
                        PHONE_NOW
                    },
                    setTime = {},
                    nowEpochSeconds = { PHONE_NOW.toDouble() },
                    commandTimeout = 5.seconds,
                    driftToleranceSeconds = 5.0,
                    logInfo = {},
                    logWarning = { warnings += it },
                ),
            )
        }

        assertTrue(warnings.isEmpty())
    }

    @Test
    fun anErrorIsLoggedAndDoesNotSetTime() = runTest {
        var setCalls = 0
        val warnings = mutableListOf<String>()
        syncRadioClockIfNeeded(
            ConnectionClockSync(
                getTime = { throw ClockSyncProbeError() },
                setTime = { setCalls += 1 },
                nowEpochSeconds = { PHONE_NOW.toDouble() },
                commandTimeout = 5.seconds,
                driftToleranceSeconds = 5.0,
                logInfo = {},
                logWarning = { warnings += it },
            ),
        )
        assertEquals(0, setCalls)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("probe"))
    }

    private companion object {
        const val PHONE_NOW = 1_700_000_000L
    }
}

private class ClockSyncProbeError : Error("probe")

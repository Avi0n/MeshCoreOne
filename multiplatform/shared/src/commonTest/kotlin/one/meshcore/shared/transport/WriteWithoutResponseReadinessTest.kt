package one.meshcore.shared.transport

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class WriteWithoutResponseReadinessTest {
    @Test
    fun aReadyControllerWritesOnTheFirstAttempt() = runTest {
        var attempts = 0
        WriteWithoutResponseReadiness.write(timeout = 50.milliseconds) { attempts++ }
        assertEquals(1, attempts)
    }

    @Test
    fun aBusyControllerRetriesUntilTheWriteLands() = runTest {
        var attempts = 0
        WriteWithoutResponseReadiness.write(
            timeout = 50.milliseconds,
            retryDelay = 5.milliseconds,
        ) {
            attempts++
            if (attempts < 3) throw WriteBusyException()
        }
        assertEquals(3, attempts)
    }

    @Test
    fun aSilentControllerFailsAtTheTimeoutBackstop() = runTest {
        val busy = WriteBusyException(IllegalStateException("controller busy"))
        val error = assertFailsWith<WriteWithoutResponseTimeoutException> {
            WriteWithoutResponseReadiness.write(
                timeout = 20.milliseconds,
                retryDelay = 5.milliseconds,
            ) { throw busy }
        }
        assertTrue(error.message!!.contains("timeout"))
        assertEquals(busy, error.cause)
    }
}

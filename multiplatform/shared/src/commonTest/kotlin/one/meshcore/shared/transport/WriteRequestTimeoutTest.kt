package one.meshcore.shared.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class WriteRequestTimeoutTest {
    @Test
    fun aReadyControllerCompletesTheWrite() = runTest {
        var attempts = 0
        val completed = WriteRequestTimeout.write(timeout = 50.milliseconds) { attempts++ }
        assertTrue(completed)
        assertEquals(1, attempts)
    }

    @Test
    fun aSilentControllerFailsAtTheTimeoutBackstop() = runTest {
        var timedOut = false
        val completed = WriteRequestTimeout.write(
            timeout = 20.milliseconds,
            onTimeout = { timedOut = true },
        ) {
            delay(Duration.INFINITE)
        }
        assertFalse(completed)
        assertTrue(timedOut)
    }

    @Test
    fun aTimeoutDoesNotConsumeTheNextWrite() = runTest {
        val first = WriteRequestTimeout.write(timeout = 20.milliseconds) {
            delay(Duration.INFINITE)
        }
        var secondAttempts = 0
        val second = WriteRequestTimeout.write(timeout = 20.milliseconds) { secondAttempts++ }
        assertFalse(first)
        assertTrue(second)
        assertEquals(1, secondAttempts)
    }
}

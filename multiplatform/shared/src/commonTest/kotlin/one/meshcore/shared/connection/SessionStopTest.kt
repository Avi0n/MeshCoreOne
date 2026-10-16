package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionStopTest {
    @Test
    fun aCancelledStopIsReturnedUnchanged() = runTest {
        val cancelled = CancellationException("stop")
        val warnings = mutableListOf<String>()
        val returned = captureSessionStop(warnings::add) { throw cancelled }
        assertSame(cancelled, returned)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aCompletedStopReturnsNull() = runTest {
        val warnings = mutableListOf<String>()
        val returned = captureSessionStop(warnings::add) { }
        assertNull(returned)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aStopFailureIsLoggedAndReturnsNull() = runTest {
        val warnings = mutableListOf<String>()
        val returned = captureSessionStop(warnings::add) { error("radio down") }
        assertNull(returned)
        assertEquals(listOf("$SESSION_STOP_FAILED: radio down"), warnings)
        assertEquals("Session stop failed", SESSION_STOP_FAILED)
    }

    @Test
    fun aStopErrorIsLoggedAndReturnsNull() = runTest {
        val warnings = mutableListOf<String>()
        val returned = captureSessionStop(warnings::add) { throw SessionStopProbeError() }
        assertNull(returned)
        assertEquals(listOf("$SESSION_STOP_FAILED: probe"), warnings)
    }

    @Test
    fun tearDownCancellationIsReturnedUnchanged() = runTest {
        val cancelled = CancellationException("teardown")
        val returned = captureTearDownCancellation { throw cancelled }
        assertSame(cancelled, returned)
    }

    @Test
    fun aTearDownFailureStillPropagates() = runTest {
        val thrown = assertFailsWith<IllegalStateException> {
            captureTearDownCancellation { throw IllegalStateException("collectors") }
        }
        assertEquals("collectors", thrown.message)
    }
}

private class SessionStopProbeError : Error("probe")

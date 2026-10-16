package one.meshcore.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RunLoggedExceptionTest {
    @Test
    fun successSkipsTheFallback() = runTest {
        val warnings = mutableListOf<String>()
        val value = runLoggedException(warnings::add, FAILURE, fallback = { "fallback" }) { "stored" }
        assertEquals("stored", value)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun anExceptionIsLoggedAndTheFallbackReturns() = runTest {
        val warnings = mutableListOf<String>()
        val value = runLoggedException(warnings::add, FAILURE, fallback = { "fallback" }) { error("offline") }
        assertEquals("fallback", value)
        assertEquals(listOf("Preferences read failed: offline"), warnings)
    }

    @Test
    fun aBlankFailureMessageUsesTheUnknownLabel() = runTest {
        val warnings = mutableListOf<String>()
        runLoggedException(warnings::add, FAILURE, fallback = {}) { throw BlankLoggedException() }
        assertEquals(listOf("Preferences read failed: unknown error"), warnings)
    }

    @Test
    fun cancellationPropagatesWithoutAWarning() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            runLoggedException(warnings::add, FAILURE, fallback = {}) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun anErrorPropagatesWithoutAWarning() = runTest {
        val probe = LoggedExceptionProbeError()
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<LoggedExceptionProbeError> {
            runLoggedException(warnings::add, FAILURE, fallback = {}) { throw probe }
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
    }
}

private class LoggedExceptionProbeError : Error("probe")

private class BlankLoggedException : Exception()

private const val FAILURE = "Preferences read failed"

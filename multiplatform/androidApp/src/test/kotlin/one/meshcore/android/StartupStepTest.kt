package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StartupStepTest {
    @Test
    fun independentStepsContinueAfterAnEarlierFailure() = runBlocking {
        val warnings = mutableListOf<String>()
        val completed = mutableListOf<String>()

        runStartupStepsIndependently(
            warnings::add,
            FAILURE,
            { error("offline cache unavailable") },
            { completed += "shortcuts" },
            { completed += "settings" },
        )

        assertEquals(listOf("shortcuts", "settings"), completed)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("offline cache unavailable"))
    }

    @Test
    fun aCancelledStartupStepPropagates() = runBlocking {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        try {
            runStartupStep(warnings::add, FAILURE, fallback = {}) { throw cancelled }
            fail("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aStartupStepSuccessSkipsTheFallback() = runBlocking {
        val warnings = mutableListOf<String>()
        val value = runStartupStep(warnings::add, FAILURE, fallback = { "fallback" }) { "seeded" }
        assertEquals("seeded", value)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aStartupStepExceptionIsLoggedAndSkipped() = runBlocking {
        val warnings = mutableListOf<String>()
        runStartupStep(warnings::add, FAILURE, fallback = {}) { error("cache down") }
        assertEquals(listOf("Demo seed failed: cache down"), warnings)
    }

    @Test
    fun aStartupStepErrorPropagates() = runBlocking {
        val warnings = mutableListOf<String>()
        val probe = StartupProbeError()
        try {
            runStartupStep(warnings::add, FAILURE, fallback = {}) { throw probe }
            fail("expected error")
        } catch (thrown: StartupProbeError) {
            assertSame(probe, thrown)
        }
        assertTrue(warnings.isEmpty())
    }

    private companion object {
        const val FAILURE = "Demo seed failed"
    }
}

private class StartupProbeError : Error("probe")

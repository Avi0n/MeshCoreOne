package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.navigation.SendAdvertShortcutOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelfAdvertSendTest {
    @Test
    fun aSentAdvertReturnsSent() = runBlocking {
        val warnings = mutableListOf<String>()
        var sent = false

        val outcome = runSelfAdvertSend(
            send = { sent = true },
            logWarning = warnings::add,
        )

        assertEquals(SendAdvertShortcutOutcome.Sent, outcome)
        assertTrue(sent)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun anExceptionIsLoggedAndFailed() = runBlocking {
        val warnings = mutableListOf<String>()

        val outcome = runSelfAdvertSend(
            send = { error("radio rejected") },
            logWarning = warnings::add,
        )

        assertEquals(SendAdvertShortcutOutcome.Failed, outcome)
        assertEquals(listOf("Self-advert failed: radio rejected"), warnings)
    }

    @Test
    fun anErrorIsLoggedAndFailed() = runBlocking {
        val warnings = mutableListOf<String>()
        val fault = SelfAdvertSendProbeFault()

        val outcome = runSelfAdvertSend(
            send = { throw fault },
            logWarning = warnings::add,
        )

        assertEquals(SendAdvertShortcutOutcome.Failed, outcome)
        assertEquals(listOf("Self-advert failed: probe"), warnings)
    }

    @Test
    fun aCancelledAdvertRethrowsWithoutAWarning() = runBlocking {
        val warnings = mutableListOf<String>()
        val cancelled = CancellationException("superseded")
        var started = false

        try {
            runSelfAdvertSend(
                send = {
                    started = true
                    throw cancelled
                },
                logWarning = warnings::add,
            )
            fail("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }

        assertTrue(started)
        assertTrue(warnings.isEmpty())
    }
}

private class SelfAdvertSendProbeFault : Error("probe")

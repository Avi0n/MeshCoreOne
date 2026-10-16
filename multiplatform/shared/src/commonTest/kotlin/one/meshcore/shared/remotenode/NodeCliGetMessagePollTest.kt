package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.GetMessageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeCliGetMessagePollTest {
    @Test
    fun anEmptyQueueIsReturned() = runTest {
        val warnings = mutableListOf<String>()

        val result = pollCliGetMessage(
            read = { GetMessageResult.NoMoreMessages },
            logWarning = warnings::add,
        )

        assertSame(GetMessageResult.NoMoreMessages, result)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aReadExceptionIsLoggedAndSkipped() = runTest {
        val warnings = mutableListOf<String>()

        val result = pollCliGetMessage(
            read = { error("queue locked") },
            logWarning = warnings::add,
        )

        assertNull(result)
        assertEquals(listOf("remoteCLI: get message failed: queue locked"), warnings)
    }

    @Test
    fun aReadErrorStopsThePump() = runTest {
        val warnings = mutableListOf<String>()
        val fault = CliPollProbeFault()

        val thrown = assertFailsWith<CliPollProbeFault> {
            pollCliGetMessage(
                read = { throw fault },
                logWarning = warnings::add,
            )
        }

        assertSame(fault, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aCancelledReadIsRethrown() = runTest {
        val warnings = mutableListOf<String>()
        val cancelled = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            pollCliGetMessage(
                read = { throw cancelled },
                logWarning = warnings::add,
            )
        }

        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }
}

private class CliPollProbeFault : Error("probe")

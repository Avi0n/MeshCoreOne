package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NodeCliEventDeliveryTest {
    @Test
    fun aDeliveredEventLogsNothing() = runTest {
        val warnings = mutableListOf<String>()
        var delivered = false

        deliverCliEvent(
            deliver = { delivered = true },
            logWarning = warnings::add,
        )

        assertTrue(delivered)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aHandlerExceptionIsLoggedAndTheCollectorContinues() = runTest {
        val warnings = mutableListOf<String>()

        deliverCliEvent(
            deliver = { error("room locked") },
            logWarning = warnings::add,
        )

        assertEquals(listOf("remoteCLI: event handler failed: room locked"), warnings)
    }

    @Test
    fun aHandlerErrorStopsTheCollector() = runTest {
        val warnings = mutableListOf<String>()
        val fault = CliEventProbeFault()

        val thrown = assertFailsWith<CliEventProbeFault> {
            deliverCliEvent(
                deliver = { throw fault },
                logWarning = warnings::add,
            )
        }

        assertSame(fault, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aCancelledEventIsRethrown() = runTest {
        val warnings = mutableListOf<String>()
        val cancelled = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            deliverCliEvent(
                deliver = { throw cancelled },
                logWarning = warnings::add,
            )
        }

        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }
}

private class CliEventProbeFault : Error("probe")

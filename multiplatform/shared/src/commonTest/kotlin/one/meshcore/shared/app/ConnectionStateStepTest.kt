package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A connection-state pass logs a store fault and stays up. Cancellation and an error leave the collector.
 */
class ConnectionStateStepTest {
    @Test
    fun aStepRuns() = runTest {
        var ran = false
        runConnectionStateStep(onFault = { error("unexpected") }) {
            ran = true
        }
        assertTrue(ran)
    }

    @Test
    fun aStoreFaultIsReportedAndSwallowed() = runTest {
        val failure = IllegalStateException("db locked")
        val reported = mutableListOf<Exception>()
        runConnectionStateStep(onFault = { reported += it }) {
            throw failure
        }
        assertSame(failure, reported.single())
    }

    @Test
    fun aCancelledStepPropagatesWithoutAReport() = runTest {
        val cancelled = CancellationException("stopped")
        val reported = mutableListOf<Exception>()
        val thrown = assertFailsWith<CancellationException> {
            runConnectionStateStep(onFault = { reported += it }) {
                throw cancelled
            }
        }
        assertSame(cancelled, thrown)
        assertEquals(emptyList<Exception>(), reported)
    }

    @Test
    fun anErrorPropagatesWithoutAReport() = runTest {
        val fault = ConnectionStateProbeError()
        val reported = mutableListOf<Exception>()
        val thrown = assertFailsWith<ConnectionStateProbeError> {
            runConnectionStateStep(onFault = { reported += it }) {
                throw fault
            }
        }
        assertSame(fault, thrown)
        assertEquals(emptyList<Exception>(), reported)
    }
}

private class ConnectionStateProbeError : Error("probe")

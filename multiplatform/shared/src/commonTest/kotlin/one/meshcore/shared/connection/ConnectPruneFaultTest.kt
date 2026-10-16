package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * A connect-time history prune. Cancellation and a thrown error keep the thrown instance.
 * Any other failure is ignored so later connect work still runs.
 */
class ConnectPruneFaultTest {
    @Test
    fun aCancelledPruneKeepsTheThrownInstance() = runTest {
        val stopped = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            runConnectPrune { throw stopped }
        }

        assertSame(stopped, thrown)
    }

    @Test
    fun aPruneExceptionIsIgnored() = runTest {
        var calls = 0

        runConnectPrune {
            calls += 1
            error("db locked")
        }

        assertEquals(1, calls)
    }

    @Test
    fun aPruneErrorIsRethrown() = runTest {
        val probe = ConnectPruneProbeError()

        val thrown = assertFailsWith<ConnectPruneProbeError> {
            runConnectPrune { throw probe }
        }

        assertSame(probe, thrown)
    }
}

private class ConnectPruneProbeError : Error("probe")

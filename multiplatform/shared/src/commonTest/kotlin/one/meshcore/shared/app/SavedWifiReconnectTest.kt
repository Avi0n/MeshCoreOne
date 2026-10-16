package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A foreground Wi-Fi reconnect logs a connect fault and continues. Cancellation and an error propagate.
 */
class SavedWifiReconnectTest {
    @Test
    fun aConnectRuns() = runTest {
        var ran = false
        runSavedWifiReconnect(connect = { ran = true }, onFault = { error("unexpected") })
        assertTrue(ran)
    }

    @Test
    fun aConnectFaultIsReportedAndSwallowed() = runTest {
        val failure = IllegalStateException("refused")
        val reported = mutableListOf<Exception>()
        runSavedWifiReconnect(connect = { throw failure }, onFault = { reported += it })
        assertSame(failure, reported.single())
    }

    @Test
    fun aCancelledConnectPropagatesWithoutAReport() = runTest {
        val cancelled = CancellationException("stopped")
        val reported = mutableListOf<Exception>()
        val thrown = assertFailsWith<CancellationException> {
            runSavedWifiReconnect(connect = { throw cancelled }, onFault = { reported += it })
        }
        assertSame(cancelled, thrown)
        assertEquals(emptyList<Exception>(), reported)
    }

    @Test
    fun anErrorPropagatesWithoutAReport() = runTest {
        val fault = SavedWifiReconnectProbeError()
        val reported = mutableListOf<Exception>()
        val thrown = assertFailsWith<SavedWifiReconnectProbeError> {
            runSavedWifiReconnect(connect = { throw fault }, onFault = { reported += it })
        }
        assertSame(fault, thrown)
        assertEquals(emptyList<Exception>(), reported)
    }
}

private class SavedWifiReconnectProbeError : Error("probe")

package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.DeviceConnectedToOtherAppException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Connecting a radio from the sheet. A transport failure still shows the mapped text, and another
 * app still returns the other-app result. Cancellation and an error propagate and do not become a result.
 */
class ConnectResultTest {
    @Test
    fun aConnectReturnsSuccess() = runTest {
        var ran = false

        val result = runConnect(
            connect = { ran = true },
            transportErrorText = { null },
            fallbackErrorText = { "connect fail" },
        )

        assertEquals(ConnectResult(success = true), result)
        assertEquals(true, ran)
    }

    @Test
    fun aMappedTransportFailureKeepsThatText() = runTest {
        val result = runConnect(
            connect = { throw IllegalStateException("radio down") },
            transportErrorText = { "wifi mapped" },
            fallbackErrorText = { "connect fail" },
        )

        assertEquals(ConnectResult(success = false, message = "wifi mapped"), result)
    }

    @Test
    fun anUnmappedFailureUsesTheFallback() = runTest {
        val result = runConnect(
            connect = { error("link down") },
            transportErrorText = { null },
            fallbackErrorText = { "connect fail" },
        )

        assertEquals(ConnectResult(success = false, message = "connect fail"), result)
    }

    @Test
    fun anotherAppReturnsTheOtherAppResult() = runTest {
        val result = runConnect(
            connect = { throw DeviceConnectedToOtherAppException() },
            transportErrorText = { error("mapped") },
            fallbackErrorText = { error("fallback") },
        )

        assertEquals(ConnectResult(success = false, otherApp = true), result)
    }

    @Test
    fun aCancelledConnectPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            runConnect(
                connect = { throw cancelled },
                transportErrorText = { "wifi mapped" },
                fallbackErrorText = { "connect fail" },
            )
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun aConnectErrorPropagates() = runTest {
        val fault = ConnectProbeError()
        val thrown = assertFailsWith<ConnectProbeError> {
            runConnect(
                connect = { throw fault },
                transportErrorText = { "wifi mapped" },
                fallbackErrorText = { "connect fail" },
            )
        }

        assertSame(fault, thrown)
    }
}

private class ConnectProbeError : Error("probe")

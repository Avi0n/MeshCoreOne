package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class MapChannelSessionErrorTest {
    @Test
    fun aCancelledChannelCommandPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            mapChannelSessionError { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aChannelCommandSuccessReturnsTheValue() = runTest {
        assertEquals("saved", mapChannelSessionError { "saved" })
    }

    @Test
    fun aTypedChannelErrorIsRethrown() = runTest {
        val failure = ChannelServiceError.SaveFailed("disk")
        val thrown = assertFailsWith<ChannelServiceError.SaveFailed> {
            mapChannelSessionError { throw failure }
        }
        assertSame(failure, thrown)
    }

    @Test
    fun aTransportFailureBecomesSessionError() = runTest {
        val failure = ChannelTransportException("socket closed")
        val thrown = assertFailsWith<ChannelServiceError.SessionError> {
            mapChannelSessionError { throw failure }
        }
        assertSame(failure, thrown.cause)
    }

    @Test
    fun aChannelCommandErrorBecomesSessionError() = runTest {
        val probe = ChannelSessionProbeError()
        val thrown = assertFailsWith<ChannelServiceError.SessionError> {
            mapChannelSessionError { throw probe }
        }
        assertSame(probe, thrown.cause)
    }
}

private class ChannelSessionProbeError : Error("probe")

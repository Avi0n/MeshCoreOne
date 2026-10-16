package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Zero-hop send faults. A timeout or a send exception is no response in the ping tests; a send error
 * keeps its instance, and cancellation leaves the ping.
 */
class ContactPingSendFaultTest {
    @Test
    fun aSendErrorRethrowsTheSameError() = runTest {
        val failure = PingSendProbeError()
        val thrown = assertFailsWith<PingSendProbeError> {
            zeroHopPing(
                publicKey = ByteArray(32) { 0xEE.toByte() },
                pathHashMode = 0,
                events = MutableSharedFlow(),
                sendTrace = { _, _, _ -> throw failure },
                clock = ZeroHopPingClock(nowMs = { 0L }, randomTag = { 1L }),
            )
        }
        assertSame(failure, thrown)
    }

    @Test
    fun aCancelledSendPropagatesWithItsMessage() = runTest {
        val thrown = assertFailsWith<CancellationException> {
            zeroHopPing(
                publicKey = ByteArray(32) { 0xEE.toByte() },
                pathHashMode = 0,
                events = MutableSharedFlow(),
                sendTrace = { _, _, _ -> throw CancellationException("superseded") },
                clock = ZeroHopPingClock(nowMs = { 0L }, randomTag = { 1L }),
            )
        }
        assertEquals("superseded", thrown.message)
    }
}

private class PingSendProbeError : Error("probe")

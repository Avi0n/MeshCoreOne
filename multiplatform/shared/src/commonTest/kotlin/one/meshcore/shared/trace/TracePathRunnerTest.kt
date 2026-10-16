package one.meshcore.shared.trace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.meshcore.TraceNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TracePathRunnerTest {
    @Test
    fun parseTracePathHexRequiresUniformWidth() {
        assertNull(parseTracePathHex("", 1))
        assertNull(parseTracePathHex("AB,ABCD", 1))
        val hops = parseTracePathHex("ab,CD", 1)
        assertNotNull(hops)
        assertEquals(2, hops.size)
        assertEquals(1, hops[0].hashBytes.size)
        assertEquals(0xAB.toByte(), hops[0].hashBytes[0])
        assertEquals(0xCD.toByte(), hops[1].hashBytes[0])
    }

    @Test
    fun buildTraceResultHopsStartMidEnd() {
        val path = listOf(
            TraceNode(hashBytes = byteArrayOf(0x11), snr = 3.5),
            TraceNode(hashBytes = byteArrayOf(0x22), snr = 1.0),
        )
        val hops = buildTraceResultHops(path, deviceName = "Me") { bytes ->
            if (bytes.contentEquals(byteArrayOf(0x11))) "R1" else null
        }
        assertEquals(4, hops.size)
        assertTrue(hops.first().isStartNode)
        assertEquals("Me", hops.first().resolvedName)
        assertEquals(0.0, hops.first().snr)
        assertEquals("R1", hops[1].resolvedName)
        assertEquals(3.5, hops[1].snr)
        assertTrue(hops.last().isEndNode)
        assertEquals(1.0, hops.last().snr)
    }

    @Test
    fun sanitizedFloodTimeoutScalesAddsGraceThenClamps() {
        // A missing hint yields the profile default rather than expiring at once.
        assertEquals(30_000L, sanitizedFloodTimeoutMs(0))
        // The two interior cases are the whole point: they pass only when the hint is scaled by 1.2
        // AND the 8 s return-leg grace is added. Clamp-only arithmetic returns the 5 s floor for both.
        assertEquals(11_600L, sanitizedFloodTimeoutMs(3_000))
        assertEquals(14_000L, sanitizedFloodTimeoutMs(5_000))
        assertEquals(60_000L, sanitizedFloodTimeoutMs(120_000))
    }

    @Test
    fun runTracePathSuccessBuildsResult() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val pathBytes = byteArrayOf(0x11)
        val result = runTracePath(
            TracePathRequest(
                pathBytes = pathBytes,
                hashSize = 1,
                flags = 0,
                events = events,
                sendTrace = { sentTag, _, _ ->
                    events.tryEmit(
                        MeshEvent.TraceData(
                            TraceInfo(
                                tag = sentTag,
                                authCode = 0,
                                flags = 0,
                                pathLength = 1,
                                path = listOf(TraceNode(hashBytes = byteArrayOf(0x11), snr = 4.0)),
                            ),
                        ),
                    )
                    MessageSentInfo(route = 0, expectedAck = ByteArray(0), suggestedTimeoutMs = 5_000)
                },
                deviceName = "Radio",
                failureMessages = TraceFailureMessages("fail", "send fail"),
                nowMs = { 1_000L },
                randomTag = { 42L },
            ),
        )
        assertTrue(result.success)
        assertEquals(3, result.hops.size)
        assertEquals(4.0, result.hops[1].snr)
        assertEquals("Radio", result.hops.first().resolvedName)
    }

    @Test
    fun anUnacknowledgedSendUsesTheDefaultResponseDeadline() = runTest {
        val result = runTracePath(
            TracePathRequest(
                pathBytes = byteArrayOf(0x11),
                hashSize = 1,
                flags = 0,
                events = MutableSharedFlow(),
                sendTrace = { _, _, _ -> throw IllegalStateException("radio down") },
                deviceName = "Radio",
                failureMessages = TraceFailureMessages("no reply", "send fail"),
                nowMs = { 0L },
                randomTag = { 7L },
            ),
        )

        assertTrue(!result.success)
        assertEquals("no reply", result.errorMessage)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun aMatchingResponseAfterTheSendThrowsStillSucceeds() = runTest {
        val events = MutableSharedFlow<MeshEvent>()
        val result = runTracePath(
            TracePathRequest(
                pathBytes = byteArrayOf(0x11), hashSize = 1, flags = 0, events = events,
                sendTrace = { tag, _, _ ->
                    backgroundScope.launch {
                        delay(100L)
                        events.emit(MeshEvent.TraceData(TraceInfo(tag, 0, 0, 0, emptyList())))
                    }
                    error("missing command ACK")
                },
                deviceName = "Radio", failureMessages = TraceFailureMessages("no reply", "send fail"),
                nowMs = { testScheduler.currentTime }, randomTag = { 7L },
            ),
        )
        assertTrue(result.success)
        assertEquals(100, result.durationMs)
    }

    @Test
    fun runTracePathTimeoutIsNoResponse() = runTest {
        val result = runTracePath(
            TracePathRequest(
                pathBytes = byteArrayOf(0x11),
                hashSize = 1,
                flags = 0,
                events = MutableSharedFlow(),
                sendTrace = { _, _, _ ->
                    MessageSentInfo(route = 0, expectedAck = ByteArray(0), suggestedTimeoutMs = 0)
                },
                deviceName = "Radio",
                failureMessages = TraceFailureMessages("no reply", "send fail"),
                nowMs = { 0L },
                randomTag = { 7L },
            ),
        )

        assertTrue(!result.success)
        assertEquals("no reply", result.errorMessage)
    }

    @Test
    fun runTracePathEmptyPathFailsImmediately() = runTest {
        val result = runTracePath(
            TracePathRequest(
                pathBytes = ByteArray(0),
                hashSize = 1,
                flags = 0,
                events = MutableSharedFlow(),
                sendTrace = { _, _, _ -> error("should not send") },
                deviceName = "Radio",
                failureMessages = TraceFailureMessages("No path", "send fail"),
                nowMs = { 0L },
            ),
        )
        assertTrue(!result.success)
        assertEquals("No path", result.errorMessage)
    }

    @Test
    fun aCancelledTraceSendRethrows() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            runTracePath(traceRequest { _, _, _ -> throw cancelled })
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun aTraceSendErrorPropagates() = runTest {
        val fault = TraceRunProbeFault()
        val thrown = assertFailsWith<TraceRunProbeFault> {
            runTracePath(traceRequest { _, _, _ -> throw fault })
        }

        assertSame(fault, thrown)
    }

    private fun traceRequest(sendTrace: suspend (Long, Int, ByteArray) -> MessageSentInfo) = TracePathRequest(
        pathBytes = byteArrayOf(0x11),
        hashSize = 1,
        flags = 0,
        events = MutableSharedFlow(),
        sendTrace = sendTrace,
        deviceName = "Radio",
        failureMessages = TraceFailureMessages("no reply", "send fail"),
        nowMs = { 0L },
        randomTag = { 7L },
    )
}

private class TraceRunProbeFault : Error("probe")

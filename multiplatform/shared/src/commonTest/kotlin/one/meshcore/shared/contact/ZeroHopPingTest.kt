package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.meshcore.TraceNode
import one.meshcore.shared.tools.RxLogPayloadType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ZeroHopPingTest {
    @Test
    fun successUsesMatchingTraceSnr() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val tag = 42L
        val result = zeroHopPing(
            publicKey = ByteArray(32) { 0xAB.toByte() },
            pathHashMode = 0,
            events = events,
            sendTrace = { sentTag, _, _ ->
                events.tryEmit(
                    MeshEvent.TraceData(
                        TraceInfo(
                            tag = sentTag,
                            authCode = 0,
                            flags = 0,
                            pathLength = 2,
                            path = listOf(
                                TraceNode(hashBytes = byteArrayOf(0x01), snr = 5.5),
                                TraceNode(hashBytes = null, snr = 3.0),
                            ),
                        ),
                    ),
                )
                MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 5_000)
            },
            clock = ZeroHopPingClock(nowMs = { 1_000L }, randomTag = { tag }),
        )
        val success = assertIs<ContactPingResult.Success>(result)
        assertEquals(0, success.latencyMs)
        assertEquals(5.5, success.snrThere)
        assertEquals(3.0, success.snrBack)
    }

    @Test
    fun successInvokesOnHeard() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        var heard = 0
        val result = zeroHopPing(
            publicKey = ByteArray(32) { 0xAB.toByte() },
            pathHashMode = 0,
            events = events,
            sendTrace = { sentTag, _, _ ->
                events.tryEmit(
                    MeshEvent.TraceData(
                        TraceInfo(
                            tag = sentTag,
                            authCode = 0,
                            flags = 0,
                            pathLength = 2,
                            path = listOf(
                                TraceNode(hashBytes = byteArrayOf(0x01), snr = 5.5),
                                TraceNode(hashBytes = null, snr = 3.0),
                            ),
                        ),
                    ),
                )
                MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 5_000)
            },
            clock = ZeroHopPingClock(
                nowMs = { 1_000L },
                randomTag = { 42L },
                onHeard = { heard += 1 },
            ),
        )
        assertIs<ContactPingResult.Success>(result)
        assertEquals(1, heard)
    }

    @Test
    fun successUsesMatchingRxLogTraceSnr() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val tag = 0x01020304L
        val result = zeroHopPing(
            publicKey = ByteArray(32) { 0xCD.toByte() },
            pathHashMode = 0,
            events = events,
            sendTrace = { _, _, _ ->
                // Tag LE: 04 03 02 01; path last node Int8 22 → SNR 5.5; local SNR 3.0
                events.tryEmit(
                    MeshEvent.RxLogData(
                        ParsedRxLogData(
                            snr = 3.0,
                            rssi = -70,
                            rawPayload = ByteArray(0),
                            routeType = 0,
                            payloadType = RxLogPayloadType.TRACE,
                            payloadVersion = 0,
                            payloadTypeBits = RxLogPayloadType.TRACE,
                            pathLength = 1,
                            pathNodes = listOf(22),
                            packetPayload = byteArrayOf(0x04, 0x03, 0x02, 0x01, 0x00),
                            packetHash = "ping-rx",
                        ),
                    ),
                )
                MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 5_000)
            },
            clock = ZeroHopPingClock(nowMs = { 1_000L }, randomTag = { tag }),
        )
        val success = assertIs<ContactPingResult.Success>(result)
        assertEquals(5.5, success.snrThere)
        assertEquals(3.0, success.snrBack)
    }

    @Test
    fun traceWidthAndFlagsFollowTheDeviceHashMode() = runTest {
        // A radio on hash mode 1 (2-byte hops) or 2 (4-byte hops) parses the trace target at that width;
        // a 1-byte prefix targets a truncated key and the ping never resolves.
        val key = ByteArray(32) { (it + 1).toByte() }
        val sent = mutableListOf<Pair<Int, ByteArray>>()
        for ((mode, width) in listOf(0 to 1, 1 to 2, 2 to 4)) {
            zeroHopPing(
                publicKey = key,
                pathHashMode = mode,
                events = MutableSharedFlow(),
                sendTrace = { _, flags, path ->
                    sent += flags to path
                    MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 1_000)
                },
                clock = ZeroHopPingClock(nowMs = { 0L }, randomTag = { 7L }),
            )
            val (flags, path) = sent.last()
            assertEquals(mode, flags, "flags must declare the device hash mode")
            assertEquals(width, path.size, "hop width for mode $mode")
            assertContentEquals(key.copyOf(width), path)
        }
    }

    @Test
    fun snrFromTraceRxLogRejectsWrongTagOrType() {
        val payload = byteArrayOf(0x04, 0x03, 0x02, 0x01)
        val matching = ParsedRxLogData(
            snr = 2.0,
            rawPayload = ByteArray(0),
            routeType = 0,
            payloadType = RxLogPayloadType.TRACE,
            payloadVersion = 0,
            payloadTypeBits = RxLogPayloadType.TRACE,
            pathLength = 0,
            pathNodes = emptyList(),
            packetPayload = payload,
            packetHash = "a",
        )
        assertEquals(SnrPair(0.0, 2.0), snrFromTraceRxLog(matching, 0x01020304L))
        assertNull(snrFromTraceRxLog(matching, 99L))
        assertNull(
            snrFromTraceRxLog(
                matching.copy(payloadType = RxLogPayloadType.TEXT_MESSAGE),
                0x01020304L,
            ),
        )
    }

    @Test
    fun aTimeoutIsNoResponseNotEnglish() = runTest {
        val events = MutableSharedFlow<MeshEvent>()
        val result = zeroHopPing(
            publicKey = ByteArray(32) { 0xEE.toByte() },
            pathHashMode = 0,
            events = events,
            sendTrace = { _, _, _ ->
                MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 1_000)
            },
            clock = ZeroHopPingClock(nowMs = { 0L }, randomTag = { 1L }),
        )
        assertIs<ContactPingResult.NoResponse>(result)
    }

    @Test
    fun aSendFailureIsNoResponseNotEnglish() = runTest {
        val result = zeroHopPing(
            publicKey = ByteArray(32) { 0xEE.toByte() },
            pathHashMode = 0,
            events = MutableSharedFlow(),
            sendTrace = { _, _, _ -> error("radio down") },
            clock = ZeroHopPingClock(nowMs = { 0L }, randomTag = { 1L }),
        )
        assertIs<ContactPingResult.NoResponse>(result)
    }

    /**
     * The zero-hop profile of the iOS `FirmwareSuggestedTimeout`: scale by 1.2, then clamp to 1–30 s.
     * Waiting the bare hint reports "No response" on links iOS completes.
     */
    @Test
    fun sanitizedTimeoutScalesTheHintThenClamps() {
        // Only a missing hint falls back to the default.
        assertEquals(5_000L, sanitizedZeroHopTimeoutMs(0))
        // A sub-second scaled hint clamps up to the 1 s floor rather than jumping to the 5 s default.
        assertEquals(1_000L, sanitizedZeroHopTimeoutMs(500))
        // In-band hints carry the 1.2x slack.
        assertEquals(1_200L, sanitizedZeroHopTimeoutMs(1_000))
        assertEquals(12_000L, sanitizedZeroHopTimeoutMs(10_000))
        // 24 s scaled to 28.8 s stays under the ceiling; 60 s scaled to 72 s clamps to it.
        assertEquals(28_800L, sanitizedZeroHopTimeoutMs(24_000))
        assertEquals(30_000L, sanitizedZeroHopTimeoutMs(60_000))
    }

    @Test
    fun aCancelledSendPropagates() = runTest {
        val cancelled = assertFailsWith<CancellationException> {
            zeroHopPing(
                publicKey = ByteArray(32) { 0xEE.toByte() },
                pathHashMode = 0,
                events = MutableSharedFlow(),
                sendTrace = { _, _, _ -> throw CancellationException("superseded") },
                clock = ZeroHopPingClock(nowMs = { 0L }, randomTag = { 1L }),
            )
        }
        assertEquals("superseded", cancelled.message)
    }

    @Test
    fun aSendErrorPropagates() = runTest {
        assertFailsWith<ZeroHopPingProbeError> {
            zeroHopPing(
                publicKey = ByteArray(32) { 0xEE.toByte() },
                pathHashMode = 0,
                events = MutableSharedFlow(),
                sendTrace = { _, _, _ -> throw ZeroHopPingProbeError() },
                clock = ZeroHopPingClock(nowMs = { 0L }, randomTag = { 1L }),
            )
        }
    }

    @Test
    fun aHeardStampExceptionStillSucceeds() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val result = zeroHopPing(
            publicKey = ByteArray(32) { 0xAB.toByte() },
            pathHashMode = 0,
            events = events,
            sendTrace = { sentTag, _, _ ->
                events.tryEmit(oneHopTraceReply(sentTag))
                MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 5_000)
            },
            clock = ZeroHopPingClock(
                nowMs = { 1_000L },
                randomTag = { 42L },
                onHeard = { error("stamp failed") },
            ),
        )
        assertIs<ContactPingResult.Success>(result)
    }

    @Test
    fun aCancelledHeardStampPropagates() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val cancelled = assertFailsWith<CancellationException> {
            zeroHopPing(
                publicKey = ByteArray(32) { 0xAB.toByte() },
                pathHashMode = 0,
                events = events,
                sendTrace = { sentTag, _, _ ->
                    events.tryEmit(oneHopTraceReply(sentTag))
                    MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 5_000)
                },
                clock = ZeroHopPingClock(
                    nowMs = { 1_000L },
                    randomTag = { 42L },
                    onHeard = { throw CancellationException("superseded") },
                ),
            )
        }
        assertEquals("superseded", cancelled.message)
    }

    @Test
    fun aHeardStampErrorPropagates() = runTest {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        assertFailsWith<ZeroHopPingProbeError> {
            zeroHopPing(
                publicKey = ByteArray(32) { 0xAB.toByte() },
                pathHashMode = 0,
                events = events,
                sendTrace = { sentTag, _, _ ->
                    events.tryEmit(oneHopTraceReply(sentTag))
                    MessageSentInfo(route = 0, expectedAck = ByteArray(4), suggestedTimeoutMs = 5_000)
                },
                clock = ZeroHopPingClock(
                    nowMs = { 1_000L },
                    randomTag = { 42L },
                    onHeard = { throw ZeroHopPingProbeError() },
                ),
            )
        }
    }
}

private class ZeroHopPingProbeError : Error("probe")

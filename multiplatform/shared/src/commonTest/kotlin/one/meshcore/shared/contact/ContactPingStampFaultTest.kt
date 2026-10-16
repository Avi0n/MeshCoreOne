package one.meshcore.shared.contact

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * A heard-stamp fault after a zero-hop reply. An exception still returns success in the ping tests;
 * a store error keeps its instance.
 */
class ContactPingStampFaultTest {
    @Test
    fun aHeardStampErrorRethrowsTheSameError() = runTest {
        val failure = PingStampProbeError()
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        val thrown = assertFailsWith<PingStampProbeError> {
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
                    onHeard = { throw failure },
                ),
            )
        }
        assertSame(failure, thrown)
        assertIs<PingStampProbeError>(thrown)
    }
}

private class PingStampProbeError : Error("probe")

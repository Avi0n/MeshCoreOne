package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the [TcpMeshLink] orchestration over an in-memory [FakeTcpSocket]: connect timeout/failure,
 * outbound framing, inbound decode/delivery, the injected heartbeat's cadence on a virtual clock, the
 * receive-window staleness watchdog, half-frame-then-peer-close, and clean-disconnect silence. No real
 * networking is involved — this is the isolated, testable core the JVM socket adapter wraps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpMeshLinkTest {
    private class FakeTcpSocket : TcpSocket {
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()
        var closed = false
            private set
        var connectDelay: Duration = Duration.ZERO
        var failConnectWith: Throwable? = null
        var failReadWith: Throwable? = null

        override suspend fun connect(connectTimeout: Duration) {
            failConnectWith?.let { throw it }
            if (connectDelay > Duration.ZERO) delay(connectDelay)
        }

        override suspend fun read(): ByteArray? {
            failReadWith?.let { throw it }
            return inbound.receiveCatching().getOrNull()
        }

        override suspend fun write(bytes: ByteArray) {
            writes += bytes
        }

        override fun close() {
            closed = true
            inbound.close()
        }

        fun pushInbound(bytes: ByteArray) {
            inbound.trySend(bytes)
        }

        fun closePeer() {
            inbound.close()
        }
    }

    private class RecordingListener : MeshLinkListener {
        val received = mutableListOf<ByteArray>()
        val drops = mutableListOf<LinkDropReason>()

        override fun onReceived(bytes: ByteArray) {
            received += bytes
        }

        override fun onDisconnected(reason: LinkDropReason) {
            drops += reason
        }
    }

    @Test
    fun connectTimesOutWhenTheSocketNeverCompletes() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket().apply { connectDelay = 999.seconds }
        val link = TcpMeshLink(
            "host",
            PORT,
            { _, _ -> socket },
            backgroundScope,
            options = TcpMeshLink.Options(connectTimeout = 10.seconds),
        )

        assertFailsWith<TcpTransportException.ConnectionTimeout> { link.connect() }
        assertTrue(socket.closed, "a timed-out connect must close the socket")
    }

    @Test
    fun connectFailureIsMappedToConnectionFailed() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket().apply { failConnectWith = RuntimeException("refused") }
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)

        assertFailsWith<TcpTransportException.ConnectionFailed> { link.connect() }
        assertTrue(socket.closed)
    }

    @Test
    fun aCancelledConnectClosesTheSocketAndRethrows() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("stopped")
        val socket = FakeTcpSocket().apply { failConnectWith = cancelled }
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)

        val thrown = assertFailsWith<CancellationException> { link.connect() }

        assertEquals("stopped", thrown.message)
        assertTrue(socket.closed)
    }

    @Test
    fun aCancelledTcpOperationRethrowsTheSameCancellation() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("stopped")
        var failed = false
        val thrown = assertFailsWith<CancellationException> {
            runTcpLink(onFailure = {
                failed = true
                throw it
            }) { throw cancelled }
        }

        assertSame(cancelled, thrown)
        assertEquals(false, failed)
    }

    @Test
    fun aConnectErrorBecomesConnectionFailed() = runTest(UnconfinedTestDispatcher()) {
        val probe = TcpConnectProbeError()
        val socket = FakeTcpSocket().apply { failConnectWith = probe }
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)

        val thrown = assertFailsWith<TcpTransportException.ConnectionFailed> { link.connect() }

        assertSame(probe, thrown.cause)
        assertEquals("connection failed: probe", thrown.message)
        assertTrue(socket.closed)
    }

    @Test
    fun aFailedReadDropsTheLink() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket().apply { failReadWith = IllegalStateException("reset") }
        val listener = RecordingListener()
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)
        link.setListener(listener)

        link.connect()
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        assertTrue(socket.closed)
        assertEquals(false, link.isConnected)
    }

    @Test
    fun aCancelledReadLeavesTheLinkUp() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket().apply { failReadWith = CancellationException("stopped") }
        val listener = RecordingListener()
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)
        link.setListener(listener)

        link.connect()
        advanceUntilIdle()

        assertTrue(listener.drops.isEmpty())
        assertEquals(true, link.isConnected)
        assertEquals(false, socket.closed)
    }

    @Test
    fun tcpPipelinesChannelReadsWithoutWriteCommands() = runTest(UnconfinedTestDispatcher()) {
        val link = TcpMeshLink("host", PORT, { _, _ -> FakeTcpSocket() }, backgroundScope)
        assertEquals(false, link.advertisesWriteWithoutResponse)
        assertEquals(true, link.supportsPipelinedReads)
    }

    @Test
    fun blankHostIsRejected() = runTest(UnconfinedTestDispatcher()) {
        val link = TcpMeshLink("", PORT, { _, _ -> FakeTcpSocket() }, backgroundScope)
        assertFailsWith<TcpTransportException.InvalidHost> { link.connect() }
    }

    @Test
    fun outOfRangePortIsRejected() = runTest(UnconfinedTestDispatcher()) {
        val link = TcpMeshLink("host", 0, { _, _ -> FakeTcpSocket() }, backgroundScope)
        assertFailsWith<TcpTransportException.InvalidPort> { link.connect() }
    }

    @Test
    fun outboundBytesAreLengthPrefixFramedOnTheWire() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)
        link.connect()

        link.sendBytes(byteArrayOf(0x01, 0x02), withoutResponse = false)
        advanceUntilIdle()

        assertContentEquals(byteArrayOf(0x3C, 0x02, 0x00, 0x01, 0x02), socket.writes.single())
    }

    @Test
    fun isConnectedFollowsTheTcpSession() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val link = TcpMeshLink(
            "host",
            PORT,
            { _, _ -> socket },
            backgroundScope,
            options = TcpMeshLink.Options(receiveWindow = 999.seconds),
        )
        assertEquals(false, link.isConnected)
        link.connect()
        assertEquals(true, link.isConnected)
        link.disconnect()
        assertEquals(false, link.isConnected)
    }

    @Test
    fun inboundBytesAreDecodedAndDeliveredAsFrames() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val listener = RecordingListener()
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)
        link.setListener(listener)
        link.connect()

        socket.pushInbound(byteArrayOf(0x3E, 0x02, 0x00, 0xAA.toByte(), 0xBB.toByte()))
        advanceUntilIdle()

        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), listener.received.single())
    }

    @Test
    fun heartbeatProbeFiresOnItsInterval() = runTest(UnconfinedTestDispatcher()) {
        var probeCount = 0
        val socket = FakeTcpSocket()
        val link = TcpMeshLink(
            "host",
            PORT,
            { _, _ -> socket },
            backgroundScope,
            options = TcpMeshLink.Options(
                heartbeatInterval = 30.seconds,
                // Keep the staleness watchdog out of the way so it can't drop the link during the window.
                receiveWindow = 999.seconds,
                heartbeat = { probeCount++ },
            ),
        )
        link.connect()

        advanceTimeBy(95.seconds) // 30s, 60s, 90s
        runCurrent()

        assertEquals(3, probeCount)
    }

    @Test
    fun staleLinkWithNoInboundTrafficDropsAfterTheReceiveWindow() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val listener = RecordingListener()
        val link = TcpMeshLink(
            "host",
            PORT,
            { _, _ -> socket },
            backgroundScope,
            options = TcpMeshLink.Options(receiveWindow = 30.seconds),
        )
        link.setListener(listener)
        link.connect()

        advanceTimeBy(31.seconds)
        runCurrent()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        assertTrue(socket.closed)
    }

    @Test
    fun inboundTrafficResetsTheStalenessWindow() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val listener = RecordingListener()
        val link = TcpMeshLink(
            "host",
            PORT,
            { _, _ -> socket },
            backgroundScope,
            options = TcpMeshLink.Options(receiveWindow = 30.seconds),
        )
        link.setListener(listener)
        link.connect()

        advanceTimeBy(20.seconds)
        socket.pushInbound(byteArrayOf(0x3E, 0x00, 0x00)) // activity at t=20 resets the window
        advanceTimeBy(20.seconds) // t=40, only 20s since activity
        runCurrent()
        assertTrue(listener.drops.isEmpty(), "traffic within the window must not drop the link")

        advanceTimeBy(15.seconds) // t=55, 35s of silence since the last activity
        runCurrent()
        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
    }

    @Test
    fun anIdleLinkSurvivesRepeatedHeartbeatCyclesOnTheProductionDefaults() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val listener = RecordingListener()
        // Neither timer is overridden, exactly as the production wiring builds it. On a quiet mesh the
        // only inbound traffic is the heartbeat's own reply, which can never arrive before the probe
        // that asks for it: the window has to outlast one whole probe period plus the round trip.
        val link = TcpMeshLink(
            "host",
            PORT,
            { _, _ -> socket },
            backgroundScope,
            options = TcpMeshLink.Options(
                heartbeat = {
                    delay(PROBE_ROUND_TRIP)
                    socket.pushInbound(EMPTY_FRAME)
                },
            ),
        )
        link.setListener(listener)
        link.connect()

        repeat(IDLE_CYCLES) {
            advanceTimeBy(PRODUCTION_HEARTBEAT_INTERVAL + PROBE_ROUND_TRIP)
            runCurrent()
        }

        assertTrue(listener.drops.isEmpty(), "an idle link answering every heartbeat must not be torn down")
    }

    @Test
    fun theStalenessWindowOutlastsAWholeHeartbeatPeriod() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val listener = RecordingListener()
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope) // production defaults
        link.setListener(listener)
        link.connect()

        advanceTimeBy(PRODUCTION_HEARTBEAT_INTERVAL + 1.seconds)
        runCurrent()
        assertTrue(listener.drops.isEmpty(), "one silent heartbeat period must not close the window")

        // A link that answers nothing at all is still detected, one period later.
        advanceTimeBy(PRODUCTION_HEARTBEAT_INTERVAL)
        runCurrent()
        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
    }

    @Test
    fun halfFrameThenPeerCloseDropsWithoutDeliveringAPartialFrame() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val listener = RecordingListener()
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)
        link.setListener(listener)
        link.connect()

        socket.pushInbound(byteArrayOf(0x3E, 0x03, 0x00, 0xAA.toByte())) // needs 3 payload bytes, has 1
        socket.closePeer()
        advanceUntilIdle()

        assertTrue(listener.received.isEmpty(), "an incomplete frame must never be delivered")
        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
    }

    @Test
    fun explicitDisconnectFiresNoListenerCallback() = runTest(UnconfinedTestDispatcher()) {
        val socket = FakeTcpSocket()
        val listener = RecordingListener()
        val link = TcpMeshLink("host", PORT, { _, _ -> socket }, backgroundScope)
        link.setListener(listener)
        link.connect()

        link.disconnect()
        advanceUntilIdle()

        assertTrue(listener.drops.isEmpty(), "a user-initiated disconnect must not notify onDisconnected")
        assertTrue(socket.closed)
    }

    private companion object {
        const val PORT = 5000

        /** The heartbeat cadence the production wiring leaves at its default. */
        val PRODUCTION_HEARTBEAT_INTERVAL = 30.seconds

        /** A probe's request-plus-reply flight time over a companion radio's TCP link. */
        val PROBE_ROUND_TRIP = 500.milliseconds

        /** Enough quiet periods that a window race would have fired several times over. */
        const val IDLE_CYCLES = 5

        /** A zero-length frame: inbound traffic that decodes to nothing, standing in for a probe reply. */
        val EMPTY_FRAME = byteArrayOf(0x3E, 0x00, 0x00)
    }
}

private class TcpConnectProbeError : Error("probe")

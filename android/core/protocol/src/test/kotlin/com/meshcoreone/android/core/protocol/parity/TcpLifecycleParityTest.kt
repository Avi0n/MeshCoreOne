// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiTransportTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Transport/WiFiFrameCodec.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/PacketParser.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.parity

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventDispatcher
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.parser.PacketParser
import com.meshcoreone.android.core.protocol.transport.TrackingSocket
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiFrameException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransport
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class DeadlinePeer : AutoCloseable {
    private val listener = ServerSocket(
        0, 2, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
    ).apply { soTimeout = 2000 }
    private var client: Socket? = null
    val port: Int get() = listener.localPort

    suspend fun accept() = withTimeout(2.seconds) {
        runInterruptible(Dispatchers.IO) {
            client = listener.accept().apply { soTimeout = 2000; tcpNoDelay = true }
        }
    }

    suspend fun readExactly(size: Int): Bytes = withTimeout(2.seconds) {
        runInterruptible(Dispatchers.IO) {
            Bytes(checkNotNull(client).getInputStream().readNBytes(size).also {
                assertEquals(size, it.size, "The peer must receive the entire frame")
            })
        }
    }

    suspend fun finish(wire: Bytes) = withTimeout(2.seconds) {
        runInterruptible(Dispatchers.IO) {
            checkNotNull(client).apply {
                getOutputStream().apply { write(wire.toByteArray()); flush() }
                shutdownOutput()
            }
        }
    }

    suspend fun expectClientClosed() = withTimeout(2.seconds) {
        runInterruptible(Dispatchers.IO) {
            assertEquals(-1, checkNotNull(client).getInputStream().read(), "Actual TCP socket must close")
        }
    }

    override fun close() {
        try {
            client?.close()
        } finally {
            listener.close()
        }
    }
}

@Timeout(15)
class TcpLifecycleParityTest {
    @Test
    fun `first accept is bounded and redundant connects preserve complete ordered writes`() = runBlocking {
        DeadlinePeer().use { peer ->
            val socket = TrackingSocket()
            val creations = AtomicInteger()
            val notifications = AtomicInteger()
            val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); socket })
            try {
                transport.setDisconnectionHandler { notifications.incrementAndGet() }
                transport.setConnectionInfo("127.0.0.1", peer.port)
                val received = async(start = CoroutineStart.UNDISPATCHED) { transport.receivedData().toList() }
                withTimeout(2.seconds) { transport.connect() }
                peer.accept()
                repeat(8) { withTimeout(2.seconds) { transport.connect() } }
                assertTrue(transport.isConnected())
                assertEquals(1, creations.get())
                assertTrue(socket.tcpNoDelay)

                withTimeout(2.seconds) {
                    transport.send(Bytes.fromHex("1603"))
                    transport.sendWithoutResponse(Bytes.EMPTY)
                    transport.send(Bytes.fromHex("80ff3e3c"))
                }
                assertEquals(Bytes.fromHex("3c020016033c00003c040080ff3e3c"), peer.readExactly(15))
                withTimeout(2.seconds) { transport.disconnect() }
                assertEquals(emptyList(), withTimeout(2.seconds) { received.await() })
                peer.expectClientClosed()
                assertFalse(transport.isConnected())
                assertEquals(0, notifications.get(), "Deliberate stop must not report a peer failure")
                assertEquals(1, socket.closeCalls.get())
            } finally {
                withContext(NonCancellable) { withTimeout(2.seconds) { transport.disconnect() } }
            }
        }
    }

    @Test
    fun `peer FIN bounds callback and stream completion without losing malformed packets or filtered events`() = runBlocking {
        DeadlinePeer().use { peer ->
            val socket = TrackingSocket(maximumRead = 1)
            val transport = WiFiTransport(socketFactory = { socket })
            val disconnected = CompletableDeferred<Throwable?>()
            val notifications = AtomicInteger()
            val dispatcher = EventDispatcher { error("This bounded pipeline must not drop an event: $it") }
            val events = dispatcher.subscribe()
            val failures = dispatcher.subscribe { it is MeshEvent.ParseFailure }
            val acknowledgements = dispatcher.subscribe(EventFilter.acknowledgement(Bytes.fromHex("deadbeef")))
            val expected = listOf(
                MeshEvent.Battery(BatteryInfo(4018)),
                MeshEvent.ParseFailure(Bytes.of(1), "Battery response too short: 1 < 2"),
                MeshEvent.ParseFailure(Bytes.fromHex("fe0102"), "Unknown response code: 0xFE"),
                MeshEvent.ParseFailure(Bytes.EMPTY, "Empty packet"),
                MeshEvent.Error(255u),
                MeshEvent.Error(null),
                MeshEvent.Acknowledgement(Bytes.fromHex("deadbeef"), 500u),
                MeshEvent.Battery(BatteryInfo(3600)),
            )
            try {
                transport.setDisconnectionHandler { notifications.incrementAndGet(); disconnected.complete(it) }
                transport.setConnectionInfo("127.0.0.1", peer.port)
                val all = async(start = CoroutineStart.UNDISPATCHED) { events.toList() }
                val malformed = async(start = CoroutineStart.UNDISPATCHED) { failures.toList() }
                val acks = async(start = CoroutineStart.UNDISPATCHED) { acknowledgements.toList() }
                val raw = async(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        transport.receivedData().onEach { dispatcher.dispatch(PacketParser.parse(it)) }.toList()
                    } finally {
                        dispatcher.finishAllSubscriptions()
                    }
                }
                withTimeout(2.seconds) { transport.connect() }
                peer.accept()
                // Literal source-layout frames, not encoded by the candidate codec. Force every read to one byte.
                peer.finish(Bytes.fromHex(
                    "00ff3c3e03000cb20f3e02000c013e0300fe01023e00003e020001ff" +
                        "3e0100013e090082deadbeeff40100003e03000c100e",
                ))
                assertNull(withTimeout(2.seconds) { disconnected.await() })
                assertEquals(
                    listOf("0cb20f", "0c01", "fe0102", "", "01ff", "01", "82deadbeeff4010000", "0c100e")
                        .map(Bytes::fromHex),
                    withTimeout(2.seconds) { raw.await() },
                )
                assertEquals(expected, withTimeout(2.seconds) { all.await() })
                assertEquals(expected.filterIsInstance<MeshEvent.ParseFailure>(), withTimeout(2.seconds) { malformed.await() })
                assertEquals(listOf(expected[6]), withTimeout(2.seconds) { acks.await() })
                assertEquals(0L, dispatcher.droppedEventCount)
                assertEquals(0, dispatcher.subscriberCount)
                assertFalse(transport.isConnected())
                peer.expectClientClosed()
                withTimeout(2.seconds) { transport.disconnect() }
                assertEquals(1, notifications.get())
                assertEquals(1, socket.closeCalls.get())
            } finally {
                withContext(NonCancellable) { withTimeout(2.seconds) { transport.disconnect() } }
                dispatcher.finishAllSubscriptions()
            }
        }
    }

    @TestFactory
    fun truncatedPeerEof() = listOf(
        Triple("3e", 1, null),
        Triple("3e05", 2, null),
        Triple("3e05000102", 5, 8),
    ).map { (suffix, buffered, expectedSize) ->
        DynamicTest.dynamicTest("EOF after $suffix preserves preceding frames and typed truncation") {
            runBlocking {
                DeadlinePeer().use { peer ->
                    val socket = TrackingSocket()
                    val transport = WiFiTransport(socketFactory = { socket })
                    val disconnected = CompletableDeferred<Throwable?>()
                    val notifications = AtomicInteger()
                    val delivered = mutableListOf<Bytes>()
                    try {
                        transport.setDisconnectionHandler { notifications.incrementAndGet(); disconnected.complete(it) }
                        transport.setConnectionInfo("127.0.0.1", peer.port)
                        val result = async(start = CoroutineStart.UNDISPATCHED) {
                            assertFailsWith<WiFiFrameException.TruncatedFrame> {
                                transport.receivedData().onEach { delivered += it }.toList()
                            }
                        }
                        withTimeout(2.seconds) { transport.connect() }
                        peer.accept()
                        peer.finish(Bytes.fromHex("3e020080ff$suffix"))
                        val notification = withTimeout(2.seconds) { disconnected.await() }
                        val failure = withTimeout(2.seconds) { result.await() }
                        assertEquals(listOf(Bytes.fromHex("80ff")), delivered)
                        assertEquals(buffered, failure.bufferedBytes)
                        assertEquals(expectedSize, failure.expectedBytes)
                        assertSame(failure, notification)
                        assertFalse(transport.isConnected())
                        peer.expectClientClosed()
                        withTimeout(2.seconds) { transport.disconnect() }
                        assertEquals(1, notifications.get())
                        assertEquals(1, socket.closeCalls.get())
                    } finally {
                        withContext(NonCancellable) { withTimeout(2.seconds) { transport.disconnect() } }
                    }
                }
            }
        }
    }
}

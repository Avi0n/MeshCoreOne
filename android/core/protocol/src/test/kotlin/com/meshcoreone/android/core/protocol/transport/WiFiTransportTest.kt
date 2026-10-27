// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiTransportTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Errors/WiFiTransportError+LocalizedError.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.transport

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiFrameException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiReceiveException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransport
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WiFiTransportTest {
    @Test
    fun `Initial state is disconnected`() = socketTest {
        val transport = WiFiTransport()
        assertFalse(transport.isConnected())
        assertSame(transport.receivedData(), transport.receivedData())
        transport.disconnect()
    }

    @Test
    fun `Advertises pipelined reads without a Write-Without-Response characteristic`() = socketTest {
        val transport = WiFiTransport()
        assertTrue(transport.supportsPipelinedReads())
        assertFalse(transport.supportsWriteWithoutResponse())
        assertEquals(10_000, WiFiTransport.CONNECTION_TIMEOUT_MILLIS)
        assertEquals(5_000, WiFiTransport.WRITE_TIMEOUT_MILLIS)
        assertEquals(65_536, WiFiTransport.RECEIVE_CHUNK_SIZE)
        transport.disconnect()
    }

    @Test
    fun `Connect without configuration throws notConfigured`() = socketTest {
        val transport = WiFiTransport()
        val failure = assertFailsWith<WiFiTransportException> { transport.connect() }
        assertEquals(WiFiTransportError.NotConfigured, failure.error)
        transport.disconnect()
    }

    @Test
    fun `Connection to invalid host fails`() = socketTest {
        val socket = TrackingSocket()
        val transport = WiFiTransport(socketFactory = { socket })
        transport.setConnectionInfo("999.999.999.999", 5000)
        val failure = assertFailsWith<WiFiTransportException> { transport.connect() }
        assertTrue(failure.error is WiFiTransportError.ConnectionFailed)
        assertTrue(failure.cause is IOException)
        assertFalse(transport.isConnected())
        assertEquals(1, socket.closeCalls.get())
        transport.disconnect()
    }

    @Test
    fun `Send without connection throws notConnected`() = socketTest {
        val transport = WiFiTransport()
        for (payload in listOf(Bytes.EMPTY, Bytes.fromHex("010203"))) {
            assertEquals(
                WiFiTransportError.NotConnected,
                assertFailsWith<WiFiTransportException> { transport.send(payload) }.error,
            )
            assertEquals(
                WiFiTransportError.NotConnected,
                assertFailsWith<WiFiTransportException> { transport.sendWithoutResponse(payload) }.error,
            )
        }
        transport.disconnect()
    }

    @Test
    fun `Disconnect when not connected is safe`() = socketTest {
        val transport = WiFiTransport()
        val stream = transport.receivedData()
        transport.disconnect()
        transport.disconnect()
        assertFalse(transport.isConnected())
        assertTrue(stream.toList().isEmpty())
        assertTrue(transport.receivedData().toList().isEmpty())
    }

    @Test
    fun `connectionInfo returns configured host and port`() = socketTest {
        val transport = WiFiTransport()
        assertNull(transport.connectionInfo)
        transport.setConnectionInfo("192.168.1.50", 5000)
        assertEquals(WiFiTransport.ConnectionInfo("192.168.1.50", 5000), transport.connectionInfo)
        transport.disconnect()
        assertEquals(WiFiTransport.ConnectionInfo("192.168.1.50", 5000), transport.connectionInfo)
    }

    @Test
    fun `Disconnection handler not called within 100 ms of disconnect before connect`() = socketTest {
        val notification = CompletableDeferred<Unit>()
        val transport = WiFiTransport()
        try {
            transport.setDisconnectionHandler { notification.complete(Unit) }
            transport.disconnect()
            assertNull(withTimeoutOrNull(100) { notification.await() })
            assertFalse(transport.isConnected())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
        }
    }

    @Test
    fun `Disconnection handler not called on user-initiated disconnect`() = socketTest {
        val calls = AtomicInteger()
        val notification = CompletableDeferred<Unit>()
        val socket = TrackingSocket()
        val transport = WiFiTransport(socketFactory = { socket })
        LoopbackPeer().use { peer ->
            transport.setDisconnectionHandler { calls.incrementAndGet(); notification.complete(Unit) }
            transport.setConnectionInfo("127.0.0.1", peer.port)
            transport.connect()
            val server = peer.accept()
            socket.readStarted.await()
            val consumed = async { transport.receivedData().toList() }
            transport.disconnect()
            transport.disconnect()
            assertTrue(consumed.await().isEmpty())
            assertEquals(-1, server.readEOF())
            assertNull(withTimeoutOrNull(100) { notification.await() })
            assertEquals(0, calls.get())
            assertEquals(1, socket.closeCalls.get())
        }
    }

    @Test
    fun `Disconnection handler not called on initial connect failure`() = socketTest {
        val transport = WiFiTransport()
        val calls = AtomicInteger()
        val notification = CompletableDeferred<Unit>()
        try {
            transport.setDisconnectionHandler { calls.incrementAndGet(); notification.complete(Unit) }
            transport.setConnectionInfo("999.999.999.999", 5000)
            assertFailsWith<WiFiTransportException> { transport.connect() }
            assertNull(withTimeoutOrNull(100) { notification.await() })
            assertEquals(0, calls.get())
            assertFalse(transport.isConnected())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
        }
    }

    @Test
    fun `connect is idempotent second call does not create new TCP connection`() = socketTest {
        val creations = AtomicInteger()
        val socket = TrackingSocket()
        val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); socket })
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                val waiting = async { transport.receivedData().toList() }
                transport.connect()
                val server = peer.accept()
                assertTrue(transport.isConnected())
                val connects = List(8) { async { transport.connect() } }
                for (connect in connects) connect.await()
                assertTrue(transport.isConnected())
                peer.assertNoConnectionFor(500)
                assertEquals(1, creations.get())
                assertTrue(socket.tcpNoDelay)
                transport.send(Bytes.fromHex("1603"))
                assertEquals(Bytes.fromHex("3c02001603"), Bytes(server.readExactly(5)))
                transport.disconnect()
                assertTrue(waiting.await().isEmpty())
                assertEquals(1, socket.closeCalls.get())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Idempotence observation rejects an extra real TCP connection`() = socketTest {
        LoopbackPeer().use { peer ->
            Socket("127.0.0.1", peer.port).use {
                assertFailsWith<AssertionError> { peer.assertNoConnectionFor(500) }
            }
        }
    }

    @Test
    fun `Peer close finishes the stream and fires the disconnection handler`() = socketTest {
        val socket = TrackingSocket()
        val transport = WiFiTransport(socketFactory = { socket })
        val disconnected = CompletableDeferred<Throwable?>()
        val calls = AtomicInteger()
        LoopbackPeer().use { peer ->
            try {
                transport.setDisconnectionHandler { calls.incrementAndGet(); disconnected.complete(it) }
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                val received = async { transport.receivedData().toList() }
                server.writeWire(Bytes.fromHex("3e020080ff3e020080ff3e0000").toByteArray())
                server.shutdownOutput()
                assertNull(disconnected.await())
                assertEquals(listOf(Bytes.fromHex("80ff"), Bytes.fromHex("80ff"), Bytes.EMPTY), received.await())
                assertFalse(transport.isConnected())
                assertEquals(-1, server.readEOF())
                transport.disconnect()
                assertEquals(1, calls.get())
                assertEquals(1, socket.closeCalls.get())
                assertTrue(transport.receivedData().toList().isEmpty())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Precancelled connect cannot create a connection or leave a parked job`() = socketTest {
        val creations = AtomicInteger()
        val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); TrackingSocket() })
        LoopbackPeer().use { peer ->
            transport.setConnectionInfo("127.0.0.1", peer.port)
            val connect = async(start = CoroutineStart.LAZY) { transport.connect() }
            connect.cancel()
            assertFailsWith<CancellationException> { connect.await() }
            assertFalse(transport.isConnected())
            assertEquals(0, creations.get())
            transport.disconnect()
        }
    }

    @Test
    fun `Invalid hosts and ports fail explicitly before allocating a socket`() = socketTest {
        val creations = AtomicInteger()
        for (host in listOf("", " ", "\t\n")) {
            val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); TrackingSocket() })
            transport.setConnectionInfo(host, 5000)
            assertEquals(
                WiFiTransportError.InvalidHost,
                assertFailsWith<WiFiTransportException> { transport.connect() }.error,
            )
            transport.disconnect()
        }
        for (port in listOf(-1, 0, 65536, Int.MAX_VALUE)) {
            val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); TrackingSocket() })
            transport.setConnectionInfo("127.0.0.1", port)
            assertEquals(
                WiFiTransportError.InvalidPort,
                assertFailsWith<WiFiTransportException> { transport.connect() }.error,
            )
            transport.disconnect()
        }
        assertEquals(0, creations.get())
    }

    @Test
    fun `JVM hostname resolution reaches the real loopback listener`() = socketTest {
        val transport = WiFiTransport()
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("localhost", peer.port)
                transport.connect()
                val server = peer.accept()
                transport.sendWithoutResponse(Bytes.EMPTY)
                assertEquals(Bytes.fromHex("3c0000"), Bytes(server.readExactly(3)))
                assertTrue(transport.isConnected())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Network-scoped resolver reaches a real peer without replacing the configured hostname`() = socketTest {
        val resolved = CompletableDeferred<WiFiTransport.ConnectionInfo>()
        val transport = WiFiTransport(addressResolver = { host, port ->
            resolved.complete(WiFiTransport.ConnectionInfo(host, port))
            InetSocketAddress("127.0.0.1", port)
        })
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("radio.local", peer.port)
                transport.connect()
                val server = peer.accept()
                val target = WiFiTransport.ConnectionInfo("radio.local", peer.port)
                assertEquals(target, resolved.await())
                assertEquals(target, transport.connectionInfo)
                transport.send(Bytes.fromHex("1603"))
                assertEquals(Bytes.fromHex("3c02001603"), Bytes(server.readExactly(5)))
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Real socket one-byte reads reassemble all headers and raw unknown payloads`() = socketTest {
        val transport = WiFiTransport(socketFactory = { TrackingSocket(maximumRead = 1) })
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                val received = async { transport.receivedData().toList() }
                server.writeWire(Bytes.fromHex("00003e0400ff803e3c3e0100fe3e0000").toByteArray())
                server.shutdownOutput()
                assertEquals(
                    listOf(Bytes.fromHex("ff803e3c"), Bytes.of(0xfe), Bytes.EMPTY),
                    received.await(),
                )
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Coalesced input and concurrent writes keep every complete frame in order`() = socketTest {
        val transport = WiFiTransport()
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                val payloads = (0 until 32).map { Bytes.of(it, 0x80, 0xff) }
                val writes = payloads.map { payload -> async { transport.sendWithoutResponse(payload) } }
                val wire = server.readExactly(32 * 6)
                for (write in writes) write.await()
                val actual = wire.asList().chunked(6).map { frame ->
                    assertEquals(listOf(0x3c.toByte(), 3.toByte(), 0.toByte()), frame.take(3))
                    Bytes(frame.drop(3).toByteArray())
                }
                assertEquals(payloads.toSet(), actual.toSet())
                assertEquals(payloads.size, actual.size)
                val received = async { transport.receivedData().toList() }
                server.writeWire(Bytes.fromHex("3e0100013e0100023e010003").toByteArray())
                server.shutdownOutput()
                assertEquals(listOf(Bytes.of(1), Bytes.of(2), Bytes.of(3)), received.await())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Maximum frame reaches a real peer and oversize rejection leaves connection usable`() = socketTest {
        val transport = WiFiTransport()
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                assertFailsWith<WiFiFrameException.PayloadTooLarge> { transport.send(Bytes(ByteArray(65536))) }
                assertTrue(transport.isConnected())
                val payload = Bytes(ByteArray(65535) { 0x80.toByte() })
                val send = async { transport.send(payload) }
                val wire = Bytes(server.readExactly(65538))
                send.await()
                assertEquals(Bytes.fromHex("3cffff"), wire.prefix(3))
                assertEquals(payload, wire.slice(3, wire.size))
                val inbound = async { transport.receivedData().toList() }
                server.writeWire(byteArrayOf(0x3e, 0xff.toByte(), 0xff.toByte()) + payload.toByteArray())
                server.shutdownOutput()
                assertEquals(listOf(payload), inbound.await())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Truncated peer EOF surfaces typed failure after delivering every preceding frame`() = socketTest {
        val transport = WiFiTransport()
        val notification = CompletableDeferred<Throwable?>()
        LoopbackPeer().use { peer ->
            try {
                transport.setDisconnectionHandler { notification.complete(it) }
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                val delivered = mutableListOf<Bytes>()
                val received = async {
                    assertFailsWith<WiFiFrameException.TruncatedFrame> {
                        transport.receivedData().onEach { delivered += it }.toList()
                    }
                }
                server.writeWire(Bytes.fromHex("3e0200fe803e05000102").toByteArray())
                server.shutdownOutput()
                val failure = received.await()
                assertEquals(listOf(Bytes.fromHex("fe80")), delivered)
                assertEquals(5, failure.bufferedBytes)
                assertEquals(8, failure.expectedBytes)
                assertSame(failure, notification.await())
                assertFalse(transport.isConnected())
                assertEquals(-1, server.readEOF())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Real peer reset surfaces read failure and notifies only once`() = socketTest {
        val socket = TrackingSocket()
        val transport = WiFiTransport(socketFactory = { socket })
        val notification = CompletableDeferred<Throwable?>()
        val calls = AtomicInteger()
        LoopbackPeer().use { peer ->
            try {
                transport.setDisconnectionHandler { calls.incrementAndGet(); notification.complete(it) }
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                socket.readStarted.await()
                val received = async {
                    assertFailsWith<WiFiReceiveException> { transport.receivedData().toList() }
                }
                server.setSoLinger(true, 0)
                server.close()
                val failure = received.await()
                assertTrue(failure.cause is IOException)
                assertSame(failure, notification.await())
                assertFalse(transport.isConnected())
                transport.disconnect()
                assertEquals(1, calls.get())
                assertEquals(1, socket.closeCalls.get())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Reconnect changes streams retains configuration and clears partial old framing`() = socketTest {
        val sockets = mutableListOf<TrackingSocket>()
        val transport = WiFiTransport(socketFactory = { TrackingSocket().also { sockets += it } })
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val firstServer = peer.accept()
                val old = transport.receivedData()
                firstServer.writeWire(Bytes.fromHex("3e030001").toByteArray())
                transport.disconnect()
                assertTrue(old.toList().isEmpty())
                transport.connect()
                val secondServer = peer.accept()
                val fresh = transport.receivedData()
                assertNotSame(old, fresh)
                val received = async { fresh.toList() }
                secondServer.writeWire(Bytes.fromHex("3e0100cc").toByteArray())
                secondServer.shutdownOutput()
                assertEquals(listOf(Bytes.of(0xcc)), received.await())
                assertTrue(old.toList().isEmpty())
                assertEquals(WiFiTransport.ConnectionInfo("127.0.0.1", peer.port), transport.connectionInfo)
                assertEquals(listOf(1, 1), sockets.map { it.closeCalls.get() })
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `Cleared disconnection handler stays cleared on real peer EOF`() = socketTest {
        val calls = AtomicInteger()
        val transport = WiFiTransport()
        LoopbackPeer().use { peer ->
            try {
                transport.setDisconnectionHandler { calls.incrementAndGet() }
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                transport.clearDisconnectionHandler()
                val received = async { transport.receivedData().toList() }
                server.shutdownOutput()
                assertTrue(received.await().isEmpty())
                assertEquals(0, calls.get())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }
    }

    @Test
    fun `All eight error cases retain typed metadata and native diagnostics separate from app prose`() {
        for ((error, diagnostic, appReference) in listOf(
            Triple(WiFiTransportError.ConnectionFailed("refused"), "wifi.connection_failed: refused", "Connection failed: refused"),
            Triple(WiFiTransportError.ConnectionTimeout, "wifi.connection_timeout", "Connection timed out. Check the hostname or IP address and ensure the device is reachable."),
            Triple(WiFiTransportError.NotConnected, "wifi.not_connected", "Not connected to device."),
            Triple(WiFiTransportError.SendFailed("broken"), "wifi.send_failed: broken", "Failed to send data: broken"),
            Triple(WiFiTransportError.SendTimeout, "wifi.send_timeout", "Send operation timed out."),
            Triple(WiFiTransportError.InvalidHost, "wifi.invalid_host", "Invalid hostname or IP address."),
            Triple(WiFiTransportError.InvalidPort, "wifi.invalid_port", "Invalid port number."),
            Triple(WiFiTransportError.NotConfigured, "wifi.not_configured", "Connection not configured."),
        )) {
            val cause = IOException("underlying cause")
            val failure = WiFiTransportException(error, cause)
            assertSame(error, failure.error)
            assertSame(cause, failure.cause)
            assertEquals(diagnostic, failure.message)
            assertEquals(appReference, WiFiAppErrorReference.description(error))
            assertNotEquals(appReference, failure.message)
        }
        assertEquals(WiFiTransportError.ConnectionFailed("refused"), WiFiTransportError.ConnectionFailed("refused"))
    }
}

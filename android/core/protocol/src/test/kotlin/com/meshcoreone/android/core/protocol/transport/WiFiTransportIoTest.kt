// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiTransportTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Sources/MeshCore/Transport/WiFiTransport.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.transport

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiReceiveException
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransport
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WiFiTransportIoTest {
    @Test
    fun `Cancelling pending connect does not leave it parked or leak its socket`() = socketTest {
        val socket = ControlledSocket(holdConnect = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val scheduled = ScheduledOperations()
        val notifications = AtomicInteger()
        val notification = CompletableDeferred<Unit>()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.setDisconnectionHandler { notifications.incrementAndGet(); notification.complete(Unit) }
            val connect = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            socket.connectStarted.await()
            assertEquals(1, socket.activeBlockingCalls.get())
            scheduled.advanceBy(199)
            assertFalse(connect.isCompleted)
            assertFalse(transport.isConnected())
            assertFalse(socket.isClosed)
            scheduled.advanceBy(1)
            assertFalse(connect.isCompleted)
            assertFalse(socket.readStarted.isCompleted)
            assertEquals(1, socket.activeBlockingCalls.get())
            val cancellation = CancellationException("caller cancelled connection")
            connect.cancel(cancellation)
            scheduled.runCurrent()
            val failure = assertFailsWith<CancellationException> { scheduled.await(connect) }
            assertEquals(cancellation.message, failure.message)
            assertOriginalCause(cancellation, failure)
            assertFalse(transport.isConnected())
            assertTrue(socket.isClosed)
            assertEquals(0, socket.activeBlockingCalls.get())
            assertEquals(1, socket.closeCalls.get())
            assertNull(withTimeoutOrNull(100) { notification.await() })
            assertEquals(0, notifications.get())
            assertFailsWith<CancellationException> { transport.receivedData().toList() }
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Concurrent connect waits through pending interval and reuses the established socket`() = socketTest {
        val socket = ControlledSocket(holdConnect = true)
        val creations = AtomicInteger()
        val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); socket })
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            val first = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            socket.connectStarted.await()
            val second = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            scheduled.advanceBy(200)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertFalse(transport.isConnected())
            assertEquals(1, creations.get())
            socket.releaseConnect()
            scheduled.await(first)
            scheduled.await(second)
            socket.readStarted.await()
            assertTrue(transport.isConnected())
            assertEquals(1, creations.get())
            transport.send(Bytes.of(1))
            assertEquals(listOf(Bytes.fromHex("3c010001")), socket.frames.toList())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
        assertEquals(1, socket.closeCalls.get())
        assertEquals(0, socket.activeBlockingCalls.get())
    }

    @Test
    fun `Connection timeout is exactly ten seconds and closes and joins blocked IO`() = socketTest {
        val socket = ControlledSocket(holdConnect = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            val connect = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            socket.connectStarted.await()
            scheduled.advanceBy(9_999)
            assertFalse(connect.isCompleted)
            assertFalse(socket.isClosed)
            scheduled.advanceBy(1)
            val failure = assertFailsWith<WiFiTransportException> { scheduled.await(connect) }
            assertEquals(WiFiTransportError.ConnectionTimeout, failure.error)
            assertFalse(transport.isConnected())
            assertEquals(0, socket.activeBlockingCalls.get())
            assertEquals(1, socket.closeCalls.get())
            assertSame(failure, assertFailsWith<WiFiTransportException> { transport.receivedData().toList() })
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Native socket timeout keeps typed timeout and original cause`() = socketTest {
        val cause = SocketTimeoutException("SYN timed out")
        val socket = ControlledSocket(connectFailure = cause)
        val transport = WiFiTransport(socketFactory = { socket })
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            val failure = assertFailsWith<WiFiTransportException> { transport.connect() }
            assertEquals(WiFiTransportError.ConnectionTimeout, failure.error)
            assertOriginalCause(cause, failure)
            assertEquals(1, socket.closeCalls.get())
            assertEquals(0, socket.activeBlockingCalls.get())
        } finally {
            transport.disconnect()
        }
    }

    @Test
    fun `Cancelling an interruptible resolver closes the socket and never starts a late connect`() = socketTest {
        val socket = ControlledSocket()
        val resolving = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val transport = WiFiTransport(
            socketFactory = { socket },
            addressResolver = { _, port ->
                resolving.complete(Unit)
                try {
                    CountDownLatch(1).await()
                    InetSocketAddress("127.0.0.1", port)
                } finally {
                    exited.complete(Unit)
                }
            },
        )
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("radio.local", 5000)
            val connect = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            resolving.await()
            connect.cancel()
            scheduled.runCurrent()
            assertFailsWith<CancellationException> { scheduled.await(connect) }
            assertTrue(exited.isCompleted)
            assertFalse(socket.connectStarted.isCompleted)
            assertFalse(transport.isConnected())
            assertEquals(1, socket.closeCalls.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Connection deadline includes interruptible address resolution`() = socketTest {
        val socket = ControlledSocket()
        val resolving = CompletableDeferred<Unit>()
        val transport = WiFiTransport(
            socketFactory = { socket },
            addressResolver = { _, port ->
                resolving.complete(Unit)
                CountDownLatch(1).await()
                InetSocketAddress("127.0.0.1", port)
            },
        )
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("radio.local", 5000)
            val connect = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            resolving.await()
            scheduled.advanceBy(9_999)
            assertFalse(connect.isCompleted)
            scheduled.advanceBy(1)
            val failure = assertFailsWith<WiFiTransportException> { scheduled.await(connect) }
            assertEquals(WiFiTransportError.ConnectionTimeout, failure.error)
            assertFalse(socket.connectStarted.isCompleted)
            assertEquals(1, socket.closeCalls.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Connect failure exposes reason cause and any cleanup failure without fake success`() = socketTest {
        val cause = IOException("connection refused")
        val cleanup = IOException("close failed after closing")
        val socket = ControlledSocket(connectFailure = cause, closeFailure = cleanup)
        val transport = WiFiTransport(socketFactory = { socket })
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            val failure = assertFailsWith<WiFiTransportException> { transport.connect() }
            assertEquals(WiFiTransportError.ConnectionFailed("connection refused"), failure.error)
            assertOriginalCause(cause, failure)
            assertEquals(listOf(cleanup), failure.suppressed.toList())
            assertFalse(transport.isConnected())
            assertEquals(1, socket.closeCalls.get())
        } finally {
            transport.disconnect()
        }
    }

    @Test
    fun `Disconnect interrupts and joins a pending connect without invoking the loss handler`() = socketTest {
        val socket = ControlledSocket(holdConnect = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val calls = AtomicInteger()
        val scheduled = ScheduledOperations()
        try {
            transport.setDisconnectionHandler { calls.incrementAndGet() }
            transport.setConnectionInfo("127.0.0.1", 5000)
            val old = transport.receivedData()
            val connect = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            socket.connectStarted.await()
            transport.disconnect()
            assertEquals(0, socket.activeBlockingCalls.get())
            val failure = assertFailsWith<WiFiTransportException> { scheduled.await(connect) }
            assertEquals(WiFiTransportError.ConnectionFailed("Disconnected"), failure.error)
            assertTrue(old.toList().isEmpty())
            assertEquals(0, calls.get())
            assertEquals(1, socket.closeCalls.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Queued connect requests from before disconnect cannot reopen the connection`() = socketTest {
        val socket = ControlledSocket(holdConnect = true)
        val creations = AtomicInteger()
        val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); socket })
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            val first = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            socket.connectStarted.await()
            val queued = scheduled.scope.async { transport.connect() }
            scheduled.runCurrent()
            transport.disconnect()
            assertFailsWith<WiFiTransportException> { scheduled.await(first) }
            val failure = assertFailsWith<WiFiTransportException> { scheduled.await(queued) }
            assertEquals(WiFiTransportError.ConnectionFailed("Disconnected"), failure.error)
            assertEquals(1, creations.get())
            assertEquals(1, socket.closeCalls.get())
            assertFalse(transport.isConnected())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `New connection waits for old connect cleanup and uses only its fresh real socket`() = socketTest {
        val oldSocket = ControlledSocket(holdConnect = true)
        val newSocket = TrackingSocket()
        val creations = AtomicInteger()
        val transport = WiFiTransport(socketFactory = {
            if (creations.getAndIncrement() == 0) oldSocket else newSocket
        })
        val scheduled = ScheduledOperations()
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                val oldConnect = scheduled.scope.async { transport.connect() }
                scheduled.runCurrent()
                oldSocket.connectStarted.await()
                transport.disconnect()
                val newConnect = scheduled.scope.async(start = CoroutineStart.UNDISPATCHED) { transport.connect() }
                assertFalse(newConnect.isCompleted)
                scheduled.await(newConnect)
                val server = peer.accept()
                val oldFailure = assertFailsWith<WiFiTransportException> { scheduled.await(oldConnect) }
                assertEquals(WiFiTransportError.ConnectionFailed("Disconnected"), oldFailure.error)
                assertTrue(transport.isConnected())
                transport.send(Bytes.fromHex("1603"))
                assertEquals(Bytes.fromHex("3c02001603"), Bytes(server.readExactly(5)))
                transport.disconnect()
                assertEquals(1, oldSocket.closeCalls.get())
                assertEquals(1, newSocket.closeCalls.get())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
                scheduled.close()
            }
        }
    }

    @Test
    fun `Whole write operation remains serialized while the first write is suspended`() = socketTest {
        val socket = ControlledSocket(holdWrites = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.connect()
            socket.readStarted.await()
            val first = scheduled.scope.async { transport.send(Bytes.of(1)) }
            scheduled.runCurrent()
            socket.writeStarted.await()
            val queued = scheduled.scope.async { transport.sendWithoutResponse(Bytes.of(2)) }
            scheduled.runCurrent()
            assertEquals(listOf(Bytes.fromHex("3c010001")), socket.frames.toList())
            assertFalse(first.isCompleted)
            assertFalse(queued.isCompleted)
            socket.releaseWrites()
            scheduled.await(first)
            scheduled.await(queued)
            assertEquals(listOf(Bytes.fromHex("3c010001"), Bytes.fromHex("3c010002")), socket.frames.toList())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
        assertEquals(0, socket.activeBlockingCalls.get())
        assertEquals(1, socket.closeCalls.get())
    }

    @Test
    fun `Write timeout is exactly five seconds terminates the generation and notifies once`() = socketTest {
        val socket = ControlledSocket(holdWrites = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val scheduled = ScheduledOperations()
        val notification = CompletableDeferred<Throwable?>()
        val calls = AtomicInteger()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.setDisconnectionHandler { calls.incrementAndGet(); notification.complete(it) }
            transport.connect()
            socket.readStarted.await()
            val send = scheduled.scope.async { transport.send(Bytes.of(0xfe)) }
            scheduled.runCurrent()
            socket.writeStarted.await()
            scheduled.advanceBy(4_999)
            assertFalse(send.isCompleted)
            assertTrue(transport.isConnected())
            scheduled.advanceBy(1)
            val failure = assertFailsWith<WiFiTransportException> { scheduled.await(send) }
            assertEquals(WiFiTransportError.SendTimeout, failure.error)
            assertSame(failure, notification.await())
            assertSame(failure, assertFailsWith<WiFiTransportException> { transport.receivedData().toList() })
            assertFalse(transport.isConnected())
            transport.disconnect()
            assertEquals(0, socket.activeBlockingCalls.get())
            assertEquals(1, socket.closeCalls.get())
            assertEquals(1, calls.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Cancelling an active write closes the socket propagates cancellation and fails queued writes`() = socketTest {
        val socket = ControlledSocket(holdWrites = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.connect()
            socket.readStarted.await()
            val first = scheduled.scope.async { transport.send(Bytes.of(1)) }
            scheduled.runCurrent()
            socket.writeStarted.await()
            val queued = scheduled.scope.async { transport.send(Bytes.of(2)) }
            scheduled.runCurrent()
            val cancellation = CancellationException("caller cancelled write")
            first.cancel(cancellation)
            scheduled.runCurrent()
            val cancelled = assertFailsWith<CancellationException> { scheduled.await(first) }
            assertEquals(cancellation.message, cancelled.message)
            assertOriginalCause(cancellation, cancelled)
            val failure = assertFailsWith<WiFiTransportException> { scheduled.await(queued) }
            assertEquals(WiFiTransportError.NotConnected, failure.error)
            assertEquals(listOf(Bytes.fromHex("3c010001")), socket.frames.toList())
            assertFalse(transport.isConnected())
            assertEquals(1, socket.closeCalls.get())
            transport.disconnect()
            assertEquals(0, socket.activeBlockingCalls.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Cancelling a queued write does not close the live connection or send its bytes`() = socketTest {
        val socket = ControlledSocket(holdWrites = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.connect()
            socket.readStarted.await()
            val first = scheduled.scope.async { transport.send(Bytes.of(1)) }
            scheduled.runCurrent()
            socket.writeStarted.await()
            val queued = scheduled.scope.async { transport.send(Bytes.of(2)) }
            scheduled.runCurrent()
            queued.cancel()
            assertFailsWith<CancellationException> { scheduled.await(queued) }
            assertTrue(transport.isConnected())
            assertEquals(0, socket.closeCalls.get())
            socket.releaseWrites()
            scheduled.await(first)
            assertEquals(listOf(Bytes.fromHex("3c010001")), socket.frames.toList())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Send failure preserves reason and cause and closes the established generation`() = socketTest {
        val cause = IOException("broken pipe")
        val socket = ControlledSocket(writeFailure = cause)
        val transport = WiFiTransport(socketFactory = { socket })
        val notification = CompletableDeferred<Throwable?>()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.setDisconnectionHandler { notification.complete(it) }
            transport.connect()
            socket.readStarted.await()
            val failure = assertFailsWith<WiFiTransportException> { transport.sendWithoutResponse(Bytes.of(1)) }
            assertEquals(WiFiTransportError.SendFailed("broken pipe"), failure.error)
            assertOriginalCause(cause, failure)
            assertSame(failure, notification.await())
            assertSame(failure, assertFailsWith<WiFiTransportException> { transport.receivedData().toList() })
            assertEquals(1, socket.closeCalls.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
        }
        assertEquals(0, socket.activeBlockingCalls.get())
    }

    @Test
    fun `Disconnect drains blocked write and read jobs before returning`() = socketTest {
        val socket = ControlledSocket(holdWrites = true)
        val transport = WiFiTransport(socketFactory = { socket })
        val scheduled = ScheduledOperations()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.connect()
            socket.readStarted.await()
            val sending = scheduled.scope.async { transport.send(Bytes.of(1)) }
            scheduled.runCurrent()
            socket.writeStarted.await()
            transport.disconnect()
            assertEquals(0, socket.activeBlockingCalls.get())
            assertEquals(1, socket.closeCalls.get())
            val failure = assertFailsWith<WiFiTransportException> { scheduled.await(sending) }
            assertTrue(failure.error is WiFiTransportError.SendFailed)
            assertTrue(transport.receivedData().toList().isEmpty())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            scheduled.close()
        }
    }

    @Test
    fun `Late old-generation send failure cannot close or notify the new real generation`() = socketTest {
        val oldSocket = ControlledSocket(holdWrites = true)
        val newSocket = TrackingSocket()
        val creations = AtomicInteger()
        val transport = WiFiTransport(socketFactory = {
            if (creations.getAndIncrement() == 0) oldSocket else newSocket
        })
        val scheduled = ScheduledOperations()
        val newNotifications = AtomicInteger()
        val newNotification = CompletableDeferred<Unit>()
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                oldSocket.readStarted.await()
                val oldSend = scheduled.scope.async { transport.send(Bytes.of(1)) }
                scheduled.runCurrent()
                oldSocket.writeStarted.await()
                transport.disconnect()
                transport.connect()
                val server = peer.accept()
                transport.setDisconnectionHandler { newNotifications.incrementAndGet(); newNotification.complete(Unit) }
                assertFailsWith<WiFiTransportException> { scheduled.await(oldSend) }
                assertTrue(transport.isConnected())
                transport.send(Bytes.of(2))
                assertEquals(Bytes.fromHex("3c010002"), Bytes(server.readExactly(4)))
                assertNull(withTimeoutOrNull(100) { newNotification.await() })
                assertEquals(0, newNotifications.get())
                assertEquals(1, oldSocket.closeCalls.get())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
                scheduled.close()
            }
        }
    }

    @Test
    fun `Read failure preserves the original IO cause in both stream and handler`() = socketTest {
        val cause = IOException("fixture receive error")
        val socket = ControlledSocket()
        val transport = WiFiTransport(socketFactory = { socket })
        val notification = CompletableDeferred<Throwable?>()
        try {
            transport.setConnectionInfo("127.0.0.1", 5000)
            transport.setDisconnectionHandler { notification.complete(it) }
            transport.connect()
            socket.readStarted.await()
            socket.failRead(cause)
            val failure = assertFailsWith<WiFiReceiveException> { transport.receivedData().toList() }
            assertOriginalCause(cause, failure)
            assertSame(failure, notification.await())
            assertFalse(transport.isConnected())
            assertEquals(1, socket.closeCalls.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
        }
        assertEquals(0, socket.activeBlockingCalls.get())
    }

    @Test
    fun `Close failure is surfaced to disconnect and stream instead of being silently discarded`() = socketTest {
        val cause = IOException("fixture close failure")
        val socket = ControlledSocket(closeFailure = cause)
        val transport = WiFiTransport(socketFactory = { socket })
        transport.setConnectionInfo("127.0.0.1", 5000)
        transport.connect()
        socket.readStarted.await()
        val failure = assertFailsWith<WiFiTransportException> { transport.disconnect() }
        assertSame(cause, failure.cause)
        assertTrue(failure.error is WiFiTransportError.ConnectionFailed)
        assertSame(failure, assertFailsWith<WiFiTransportException> { transport.receivedData().toList() })
        transport.disconnect()
        assertEquals(1, socket.closeCalls.get())
        assertEquals(0, socket.activeBlockingCalls.get())
        assertFalse(transport.isConnected())
    }

    @Test
    fun `Transport rejects invalid timer configuration and socket factory IO failure is typed`() = socketTest {
        assertFailsWith<IllegalArgumentException> { WiFiTransport(connectionTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { WiFiTransport(connectionTimeoutMillis = Int.MAX_VALUE.toLong() + 1) }
        assertFailsWith<IllegalArgumentException> { WiFiTransport(writeTimeoutMillis = 0) }
        val cause = IOException("socket unavailable")
        val transport = WiFiTransport(socketFactory = { throw cause })
        transport.setConnectionInfo("127.0.0.1", 5000)
        val failure = assertFailsWith<WiFiTransportException> { transport.connect() }
        assertEquals(WiFiTransportError.ConnectionFailed("socket unavailable"), failure.error)
        assertSame(cause, failure.cause)
        assertFalse(transport.isConnected())
        transport.disconnect()
    }

    @Test
    fun `Cancelling only the consumer does not close the live socket and next raw packet remains available`() = socketTest {
        val socket = TrackingSocket()
        val transport = WiFiTransport(socketFactory = { socket })
        LoopbackPeer().use { peer ->
            try {
                transport.setConnectionInfo("127.0.0.1", peer.port)
                transport.connect()
                val server = peer.accept()
                val consumer = async(start = CoroutineStart.UNDISPATCHED) { transport.receivedData().toList() }
                consumer.cancel()
                assertFailsWith<CancellationException> { consumer.await() }
                assertTrue(transport.isConnected())
                assertEquals(0, socket.closeCalls.get())
                val nextConsumer = async { transport.receivedData().toList() }
                server.writeWire(Bytes.fromHex("3e0100fe").toByteArray())
                server.shutdownOutput()
                assertEquals(listOf(Bytes.of(0xfe)), nextConsumer.await())
            } finally {
                withContext(NonCancellable) { transport.disconnect() }
            }
        }

    }

    private fun assertOriginalCause(original: Throwable, failure: Throwable) {
        assertTrue(
            generateSequence(failure) { it.cause }.any { it === original },
            "Coroutine stack recovery must retain the original cause: ${failure.stackTraceToString()}",
        )
    }
}

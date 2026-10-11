// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiTransportTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransport
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WiFiConnectionAcceptanceTest {
    @OriginalCase("WiFiTransportTests::Cancelling connect() does not leave it parked()")
    @Test fun originalTargetAndPendingDelayCancelWithinThreeSecondsWithoutExternalNetwork() = runTest {
        val started = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val endpoint = AtomicReference<SocketAddress>()
        val timeout = AtomicInteger()
        val closes = AtomicInteger()
        // Only the SYN wait is injected; the production resolver, ownership and cleanup still run.
        val socket = object : Socket() {
            override fun connect(target: SocketAddress, timeoutMillis: Int) {
                endpoint.set(target)
                timeout.set(timeoutMillis)
                started.complete(Unit)
                try {
                    release.await()
                } finally {
                    exited.complete(Unit)
                }
            }

            override fun close() {
                closes.incrementAndGet()
                release.countDown()
                super.close()
            }
        }
        val transport = WiFiTransport(socketFactory = { socket })
        try {
            transport.setConnectionInfo("192.0.2.1", 9)
            val connect = backgroundScope.async { transport.connect() }
            runCurrent()
            awaitRealCompletion(started)
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(InetSocketAddress("192.0.2.1", 9), endpoint.get())
            assertEquals(10_000, timeout.get())
            advanceTimeBy(200)
            runCurrent()
            assertEquals(200L, testScheduler.currentTime)
            assertFalse("Original 200ms pending interval must not become a precancelled call", connect.isCompleted)
            assertFalse(exited.isCompleted)
            assertEquals(0, closes.get())
            val cancellation = CancellationException("original pending connect cancelled")
            connect.cancel(cancellation)
            runCurrent()
            awaitRealCompletion(connect)
            try {
                connect.await()
                fail("A cancelled connection cannot return success")
            } catch (failure: CancellationException) {
                assertEquals(cancellation.message, failure.message)
            }
            assertTrue(exited.isCompleted)
            assertTrue(socket.isClosed)
            assertFalse(transport.isConnected())
            assertEquals(1, closes.get())
        } finally {
            withContext(NonCancellable) { transport.disconnect() }
            release.countDown()
        }
    }

    @OriginalCase("WiFiTransportTests::connect() is idempotent \u2014 second call does not create new TCP connection()")
    @Test fun originalFirstAcceptCompletesWithinTwoSecondsWithoutDuplicateConnection() = runBlocking {
        withTimeout(15_000) {
            val creations = AtomicInteger()
            val closes = AtomicInteger()
            val socket = object : Socket() {
                override fun close() {
                    closes.incrementAndGet()
                    super.close()
                }
            }
            val transport = WiFiTransport(socketFactory = { creations.incrementAndGet(); socket })
            ServerSocket().use { listener ->
                listener.bind(InetSocketAddress("127.0.0.1", 0))
                listener.soTimeout = 2_000
                try {
                    transport.setConnectionInfo("127.0.0.1", listener.localPort)
                    transport.connect()
                    assertTrue(transport.isConnected())
                    observeWithinTwoSeconds("First connection acceptance") {
                        withContext(Dispatchers.IO) { listener.accept() }
                    }.use { server ->
                        server.soTimeout = 2_000
                        val waiting = async { transport.receivedData().toList() }
                        val connects = List(8) { async { transport.connect() } }
                        connects.forEach { it.await() }
                        assertTrue(transport.isConnected())
                        assertEquals(1, creations.get())
                        assertTrue(socket.tcpNoDelay)
                        transport.send(Bytes.fromHex("1603"))
                        val frame = withContext(Dispatchers.IO) { server.getInputStream().readNBytes(5) }
                        assertEquals(Bytes.fromHex("3c02001603"), Bytes(frame))
                        transport.disconnect()
                        assertTrue(observeWithinTwoSeconds("Disconnected stream completion") { waiting.await() }.isEmpty())
                        assertTrue(socket.isClosed)
                        assertEquals(1, closes.get())
                    }
                } finally {
                    withContext(NonCancellable) { transport.disconnect() }
                }
            }
        }
    }

    @OriginalCase("WiFiTransportTests::Peer close finishes the stream and fires the disconnection handler()")
    @Test fun originalPeerCloseAcceptHandlerAndStreamEachCompleteWithinTwoSeconds() = runBlocking {
        withTimeout(15_000) {
            for (wire in listOf("", "3e020080ff3e020080ff3e0000")) {
                val closes = AtomicInteger()
                val calls = AtomicInteger()
                val disconnected = CompletableDeferred<Throwable?>()
                val socket = object : Socket() {
                    override fun close() {
                        closes.incrementAndGet()
                        super.close()
                    }
                }
                val transport = WiFiTransport(socketFactory = { socket })
                ServerSocket().use { listener ->
                    listener.bind(InetSocketAddress("127.0.0.1", 0))
                    listener.soTimeout = 2_000
                    try {
                        transport.setDisconnectionHandler {
                            calls.incrementAndGet()
                            disconnected.complete(it)
                        }
                        transport.setConnectionInfo("127.0.0.1", listener.localPort)
                        transport.connect()
                        val received = async { transport.receivedData().toList() }
                        observeWithinTwoSeconds("Peer-close connection acceptance") {
                            withContext(Dispatchers.IO) { listener.accept() }
                        }.use { server ->
                            if (wire.isNotEmpty()) {
                                withContext(Dispatchers.IO) {
                                    server.getOutputStream().apply {
                                        write(Bytes.fromHex(wire).toByteArray())
                                        flush()
                                    }
                                }
                            }
                            server.shutdownOutput()
                            assertNull(observeWithinTwoSeconds("Peer-close disconnection handler") { disconnected.await() })
                            val frames = observeWithinTwoSeconds("Peer-close received stream completion") { received.await() }
                            val expected = if (wire.isEmpty()) emptyList() else
                                listOf(Bytes.fromHex("80ff"), Bytes.fromHex("80ff"), Bytes.EMPTY)
                            assertEquals(expected, frames)
                            assertFalse(transport.isConnected())
                            transport.disconnect()
                            assertEquals(1, calls.get())
                            assertEquals(1, closes.get())
                            assertTrue(socket.isClosed)
                            assertTrue(transport.receivedData().toList().isEmpty())
                        }
                    } finally {
                        withContext(NonCancellable) { transport.disconnect() }
                    }
                }
            }
        }
    }

    private suspend fun <T> observeWithinTwoSeconds(label: String, observation: suspend () -> T): T {
        val beginning = System.nanoTime()
        val result = withTimeout(2_000) { observation() }
        assertTrue(
            "$label must complete within the original two seconds",
            System.nanoTime() - beginning < 2_000_000_000L,
        )
        return result
    }

    private suspend fun TestScope.awaitRealCompletion(job: Job) {
        val beginning = System.nanoTime()
        while (!job.isCompleted) {
            assertTrue(
                "Blocked IO must complete within the original three seconds",
                System.nanoTime() - beginning < 3_000_000_000L,
            )
            runCurrent()
            yield()
        }
        assertTrue(System.nanoTime() - beginning < 3_000_000_000L)
    }
}

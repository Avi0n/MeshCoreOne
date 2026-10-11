// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/WiFiTransportTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransport
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
            withContext(Dispatchers.Default) { withTimeout(3_000) { started.await() } }
            assertEquals(InetSocketAddress("192.0.2.1", 9), endpoint.get())
            assertEquals(10_000, timeout.get())
            advanceTimeBy(200)
            runCurrent()
            assertFalse("Original 200ms pending interval must not become a precancelled call", connect.isCompleted)
            assertFalse(exited.isCompleted)
            assertEquals(0, closes.get())
            val cancellation = CancellationException("original pending connect cancelled")
            connect.cancel(cancellation)
            runCurrent()
            withContext(Dispatchers.Default) { withTimeout(3_000) { connect.join() } }
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
}

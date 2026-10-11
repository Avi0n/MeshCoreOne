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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

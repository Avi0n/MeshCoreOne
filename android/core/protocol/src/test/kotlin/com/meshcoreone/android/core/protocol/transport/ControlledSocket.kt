// AndroidOnly: WP-108 Deterministic blocking socket phases and explicitly owned virtual-time operations.
package com.meshcoreone.android.core.protocol.transport

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

internal class ControlledSocket(
    private val holdConnect: Boolean = false,
    private val holdWrites: Boolean = false,
    private val connectFailure: IOException? = null,
    private val writeFailure: IOException? = null,
    private val closeFailure: IOException? = null,
) : Socket() {
    val connectStarted = CompletableDeferred<Unit>()
    val readStarted = CompletableDeferred<Unit>()
    val writeStarted = CompletableDeferred<Unit>()
    val closeCalls = AtomicInteger()
    val activeBlockingCalls = AtomicInteger()
    val frames = CopyOnWriteArrayList<Bytes>()
    private val connected = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val connectRelease = CountDownLatch(1)
    private val writeRelease = CountDownLatch(1)
    private val readRelease = CountDownLatch(1)
    private val readFailure = AtomicReference<IOException?>()
    var noDelay = false
        private set

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        activeBlockingCalls.incrementAndGet()
        connectStarted.complete(Unit)
        try {
            if (holdConnect) connectRelease.await()
            if (closed.get()) throw SocketException("Socket closed")
            connectFailure?.let { throw it }
            connected.set(true)
        } finally {
            activeBlockingCalls.decrementAndGet()
        }
    }

    override fun setTcpNoDelay(on: Boolean) {
        noDelay = on
    }

    override fun isConnected(): Boolean = connected.get() && !closed.get()
    override fun isClosed(): Boolean = closed.get()

    override fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int = read(ByteArray(1), 0, 1)
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            activeBlockingCalls.incrementAndGet()
            readStarted.complete(Unit)
            try {
                readRelease.await()
                readFailure.get()?.let { throw it }
                return -1
            } finally {
                activeBlockingCalls.decrementAndGet()
            }
        }
    }

    override fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            activeBlockingCalls.incrementAndGet()
            frames += Bytes(bytes.copyOfRange(offset, offset + length))
            writeStarted.complete(Unit)
            try {
                if (holdWrites) writeRelease.await()
                if (closed.get()) throw SocketException("Socket closed")
                writeFailure?.let { throw it }
            } finally {
                activeBlockingCalls.decrementAndGet()
            }
        }
    }

    fun releaseConnect() = connectRelease.countDown()

    fun releaseWrites() = writeRelease.countDown()

    fun failRead(failure: IOException) {
        readFailure.set(failure)
        readRelease.countDown()
    }

    override fun close() {
        closeCalls.incrementAndGet()
        closed.set(true)
        connected.set(false)
        connectRelease.countDown()
        writeRelease.countDown()
        readRelease.countDown()
        super.close()
        closeFailure?.let { throw it }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class ScheduledOperations {
    val scheduler = TestCoroutineScheduler()
    private val owner = SupervisorJob()
    val scope = CoroutineScope(owner + StandardTestDispatcher(scheduler))

    fun runCurrent() = scheduler.runCurrent()

    fun advanceBy(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    suspend fun <T> await(result: Deferred<T>): T {
        awaitCompletion(result)
        return result.await()
    }

    private suspend fun awaitCompletion(job: Job) {
        withTimeout(3_000) {
            while (!job.isCompleted) {
                scheduler.runCurrent()
                yield()
            }
        }
    }

    suspend fun close() {
        withContext(NonCancellable) {
            owner.cancel()
            awaitCompletion(owner)
        }
    }
}

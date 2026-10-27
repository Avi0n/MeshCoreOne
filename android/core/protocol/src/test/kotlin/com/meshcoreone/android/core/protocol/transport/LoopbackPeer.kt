// AndroidOnly: WP-108 Bounded real localhost TCP fixtures; no radio or external-network evidence.
package com.meshcoreone.android.core.protocol.transport

import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class LoopbackPeer : AutoCloseable {
    private val listener = ServerSocket().apply {
        bind(InetSocketAddress("127.0.0.1", 0))
        soTimeout = 5_000
    }
    private val accepted = CopyOnWriteArrayList<Socket>()
    val port: Int get() = listener.localPort

    suspend fun accept(): Socket = withContext(Dispatchers.IO) {
        listener.accept().apply { soTimeout = 5_000 }.also { accepted += it }
    }

    suspend fun assertNoConnectionFor(timeoutMillis: Int) = withContext(Dispatchers.IO) {
        val previousTimeout = listener.soTimeout
        listener.soTimeout = timeoutMillis
        try {
            assertFailsWith<SocketTimeoutException>("Unexpected TCP connection during the observation window") {
                listener.accept().also { accepted += it }
            }
        } finally {
            listener.soTimeout = previousTimeout
        }
    }

    override fun close() {
        for (socket in accepted) socket.close()
        listener.close()
    }
}

internal open class TrackingSocket(private val maximumRead: Int = Int.MAX_VALUE) : Socket() {
    val closeCalls = AtomicInteger()
    val readStarted = CompletableDeferred<Unit>()
    private var input: InputStream? = null

    override fun getInputStream(): InputStream =
        input ?: object : FilterInputStream(super.getInputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                readStarted.complete(Unit)
                return super.read(bytes, offset, minOf(length, maximumRead))
            }
        }.also { input = it }

    override fun close() {
        closeCalls.incrementAndGet()
        super.close()
    }
}

internal fun socketTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking { withTimeout(15_000, block) }
}

internal suspend fun Socket.readExactly(size: Int): ByteArray = withContext(Dispatchers.IO) {
    val result = getInputStream().readNBytes(size)
    assertEquals(size, result.size, "Peer ended before receiving the complete frame")
    result
}

internal suspend fun Socket.readEOF(): Int = withContext(Dispatchers.IO) { getInputStream().read() }

internal suspend fun Socket.writeWire(bytes: ByteArray) = withContext(Dispatchers.IO) {
    getOutputStream().apply {
        write(bytes)
        flush()
    }
}

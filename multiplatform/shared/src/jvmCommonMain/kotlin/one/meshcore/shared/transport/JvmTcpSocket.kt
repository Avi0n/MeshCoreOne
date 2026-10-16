package one.meshcore.shared.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration

/**
 * The one real [TcpSocket], over `java.net.Socket`, shared by Android and the desktop build (both JVM).
 * The blocking socket calls run on [Dispatchers.IO] via [runInterruptible], so coroutine cancellation
 * interrupts a parked connect or read rather than leaking a blocked thread until the OS timeout — the
 * Kotlin equivalent of the Swift transport's cancellable `connect()`.
 *
 * Kept deliberately thin: connect (with `TCP_NODELAY`), read, write, close. All framing, liveness, and
 * lifecycle logic lives in [TcpMeshLink] against the [TcpSocket] seam so it is testable without a socket.
 */
class JvmTcpSocket(private val host: String, private val port: Int) : TcpSocket {
    private val socket = Socket()

    override suspend fun connect(connectTimeout: Duration) {
        runInterruptible(Dispatchers.IO) {
            socket.tcpNoDelay = true // disable Nagle — load-bearing for channel-read latency
            socket.connect(InetSocketAddress(host, port), connectTimeout.inWholeMilliseconds.toInt())
        }
    }

    override suspend fun read(): ByteArray? = runInterruptible(Dispatchers.IO) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val count = socket.getInputStream().read(buffer)
        if (count < 0) null else buffer.copyOf(count)
    }

    override suspend fun write(bytes: ByteArray) {
        runInterruptible(Dispatchers.IO) {
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        // The Swift receive loop reads up to 64 KiB per callback; match it so a single TCP segment
        // rarely needs more than one read.
        const val READ_BUFFER_SIZE = 65536
    }
}

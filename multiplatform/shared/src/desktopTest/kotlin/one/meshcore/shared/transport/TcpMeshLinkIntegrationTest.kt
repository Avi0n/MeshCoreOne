package one.meshcore.shared.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * A real-socket round trip: [TcpMeshLink] over the production [JvmTcpSocket] against a localhost
 * `ServerSocket`. Cheap on the JVM and catches real socket-API misuse (TCP_NODELAY, stream read/write,
 * cancellation) that the in-memory fake cannot. The server echoes the received payload back as an
 * inbound frame, so this exercises both the outbound framing and the inbound decode over live TCP.
 */
class TcpMeshLinkIntegrationTest {
    @Test
    fun fullFrameRoundTripOverLocalhost() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverReceived = LinkedBlockingQueue<ByteArray>()

        val serverThread = thread {
            server.accept().use { conn ->
                conn.tcpNoDelay = true
                val input = conn.getInputStream()
                val header = readExactly(input, WiFiFrameCodec.HEADER_SIZE) ?: return@use
                val length = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0xFF) shl 8)
                val payload = readExactly(input, length) ?: return@use
                serverReceived.put(payload)

                // Echo the payload back as an inbound ('>') frame.
                conn.getOutputStream().apply {
                    write(WiFiFrameCodec.INBOUND_DELIMITER)
                    write(length and 0xFF)
                    write((length ushr 8) and 0xFF)
                    write(payload)
                    flush()
                }
                // Hold the connection open until the test tears it down.
                Thread.sleep(HOLD_OPEN_MILLIS)
            }
        }

        val inboundFrames = LinkedBlockingQueue<ByteArray>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val link = TcpMeshLink(
            host = "127.0.0.1",
            port = port,
            socketFactory = { host, socketPort -> JvmTcpSocket(host, socketPort) },
            scope = scope,
            // Wide liveness windows so neither the heartbeat nor the staleness watchdog interferes.
            options = TcpMeshLink.Options(
                heartbeatInterval = 999.seconds,
                receiveWindow = 999.seconds,
            ),
        )
        link.setListener(object : MeshLinkListener {
            override fun onReceived(bytes: ByteArray) {
                inboundFrames.put(bytes)
            }

            override fun onDisconnected(reason: LinkDropReason) = Unit
        })

        val payload = byteArrayOf(0x01, 0x02, 0x03)
        try {
            link.connect()
            link.sendBytes(payload, withoutResponse = false)

            val delivered = serverReceived.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(delivered, "server should receive the framed outbound payload")
            assertContentEquals(payload, delivered)

            val echoed = inboundFrames.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(echoed, "the decoder should deliver the echoed inbound frame")
            assertContentEquals(payload, echoed)
        } finally {
            link.disconnect()
            scope.cancel()
            serverThread.interrupt()
            serverThread.join(TIMEOUT_SECONDS * MILLIS_PER_SECOND)
            server.close()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val MILLIS_PER_SECOND = 1000L
        const val HOLD_OPEN_MILLIS = 2000L

        /** Reads exactly [count] bytes, or null if the stream ends first. */
        fun readExactly(input: InputStream, count: Int): ByteArray? {
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n < 0) return null
                read += n
            }
            return buffer
        }
    }
}

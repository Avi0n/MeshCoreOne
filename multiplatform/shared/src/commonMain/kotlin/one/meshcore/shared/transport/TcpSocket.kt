package one.meshcore.shared.transport

import kotlin.time.Duration

/**
 * The raw byte-stream socket behind [TcpMeshLink], isolated from any platform networking API so the
 * transport's orchestration (connect timeout, framed read loop, write pacing, heartbeat, staleness
 * watchdog, disconnect signalling) is unit-testable with an in-memory fake. `JvmTcpSocket`
 * (jvmCommonMain) is the one real implementation over `java.net.Socket`, shared by Android and desktop.
 *
 * This is the WiFi analogue of [BlePeripheral]: it begins once a specific host/port is chosen, and
 * exposes only the stream primitives the orchestration needs.
 */
interface TcpSocket {
    /**
     * Opens the TCP connection with Nagle disabled (`TCP_NODELAY`), honoring [connectTimeout]. Suspends
     * until connected; throws on failure. Cancellation must abandon the attempt promptly rather than
     * parking on the OS-level connect timeout.
     */
    suspend fun connect(connectTimeout: Duration)

    /**
     * Reads the next available bytes, suspending until some arrive. Returns null at end of stream (the
     * peer closed the connection). Never returns an empty array for a live stream.
     */
    suspend fun read(): ByteArray?

    /** Writes [bytes] in full; throws on I/O error. */
    suspend fun write(bytes: ByteArray)

    /** Closes the socket and releases its resources. Idempotent. */
    fun close()
}

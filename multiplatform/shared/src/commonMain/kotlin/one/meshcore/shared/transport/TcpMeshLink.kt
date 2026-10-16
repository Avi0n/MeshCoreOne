package one.meshcore.shared.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [MeshLink] over a single TCP connection, mirroring the Swift `WiFiTransport` actor and the WiFi
 * half of `ConnectionManager+WiFi`:
 *
 * - **Framing** is length-prefixed ([WiFiFrameCodec]), unlike BLE: outbound frames are encoded before
 *   the write, inbound bytes are fed to a stateful [WiFiFrameDecoder] and emitted as whole frames.
 * - **Nagle is disabled** on the socket (`TCP_NODELAY`). The companion protocol is small-frame
 *   request-response, and Nagle against the peer's delayed-ACK injects a fixed per-round-trip stall
 *   that serial channel reads pay once per channel; this is load-bearing latency, not an optimization.
 * - **Liveness** is app-level: ESP32's TCP stack ignores TCP keepalives, so the link runs an optional
 *   [heartbeat] probe on [heartbeatInterval] and a [receiveWindow] staleness watchdog that drops a link
 *   which has gone silent. The probe is injected (the manager wires it to a `getTime()` round-trip) so
 *   the transport stays protocol-agnostic; the pause-during-sync gating lives in the manager.
 * - **Drop vs clean disconnect**: an unexpected loss (peer close, read/write failure, staleness) fires
 *   [MeshLinkListener.onDisconnected] exactly once; an explicit [disconnect] fires nothing.
 *
 * One instance drives one connection: [one.meshcore.shared.connection.ConnectionManager] builds a
 * fresh link per connect attempt.
 */
class TcpMeshLink(
    private val host: String,
    private val port: Int,
    private val socketFactory: (host: String, port: Int) -> TcpSocket,
    private val scope: CoroutineScope,
    options: Options = Options(),
) : MeshLink {
    private val connectTimeout = options.connectTimeout
    private val writeTimeout = options.writeTimeout
    private val heartbeatInterval = options.heartbeatInterval
    private val receiveWindow = options.receiveWindow
    private val heartbeat = options.heartbeat

    override val supportsPipelinedReads: Boolean get() = true

    override val isConnected: Boolean
        get() = connected

    private var listener: MeshLinkListener? = null
    private var socket: TcpSocket? = null
    private val decoder = WiFiFrameDecoder()

    private val outbound = Channel<ByteArray>(Channel.UNLIMITED)

    // Conflated: each inbound read pokes the staleness watchdog; only the presence of activity within a
    // window matters, not how many reads landed.
    private val activity = Channel<Unit>(Channel.CONFLATED)

    private var readerJob: Job? = null
    private var writerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var stalenessJob: Job? = null

    // First-caller-wins teardown guard, so peer-close, a read/write failure, staleness, and an explicit
    // disconnect racing the same loss notify (or stay silent) exactly once.
    private val lifecycle = Job()
    private var connected = false

    override fun setListener(listener: MeshLinkListener?) {
        this.listener = listener
    }

    override suspend fun connect() {
        if (connected) return
        if (host.isBlank()) throw TcpTransportException.InvalidHost()
        if (port !in MIN_PORT..MAX_PORT) throw TcpTransportException.InvalidPort()

        val newSocket = socketFactory(host, port)
        socket = newSocket
        val opened = runTcpLink(
            onCancel = { cancelled ->
                newSocket.close()
                throw cancelled
            },
            onFailure = { error ->
                newSocket.close()
                throw TcpTransportException.ConnectionFailed(error.message ?: error.toString(), error)
            },
        ) {
            withTimeoutOrNull(connectTimeout) { newSocket.connect(connectTimeout) }
        }
        if (opened == null) {
            newSocket.close()
            throw TcpTransportException.ConnectionTimeout()
        }

        connected = true
        startReader(newSocket)
        startWriter(newSocket)
        startHeartbeat()
        startStalenessWatchdog()
    }

    override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) {
        // There is no ATT write characteristic over TCP; `withoutResponse` has no meaning here, so an
        // unacknowledged write degrades to the ordinary framed send (matching Swift's routing).
        outbound.trySend(bytes)
    }

    override suspend fun disconnect() {
        terminate(notify = false)
    }

    private fun startReader(activeSocket: TcpSocket) {
        readerJob = scope.launch {
            runTcpLink(
                onFailure = { terminate(notify = true) },
            ) {
                while (true) {
                    val chunk = activeSocket.read() ?: break
                    activity.trySend(Unit)
                    decoder.decode(chunk).forEach { listener?.onReceived(it) }
                }
                terminate(notify = true) // peer closed the stream
            }
        }
    }

    private fun startWriter(activeSocket: TcpSocket) {
        writerJob = scope.launch {
            runTcpLink(
                onFailure = { terminate(notify = true) },
            ) {
                for (payload in outbound) {
                    val frame = WiFiFrameCodec.encode(payload)
                    val written = withTimeoutOrNull(writeTimeout) { activeSocket.write(frame) }
                    if (written == null) {
                        terminate(notify = true) // write timed out
                        return@runTcpLink
                    }
                }
            }
        }
    }

    private fun startHeartbeat() {
        val probe = heartbeat ?: return
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatInterval)
                probe()
            }
        }
    }

    private fun startStalenessWatchdog() {
        stalenessJob = scope.launch {
            while (true) {
                val beat = withTimeoutOrNull(receiveWindow) { activity.receiveCatching().getOrNull() }
                if (beat == null) {
                    terminate(notify = true) // no inbound traffic within the receive window
                    return@launch
                }
            }
        }
    }

    private fun terminate(notify: Boolean) {
        if (!lifecycle.complete()) return
        connected = false
        readerJob?.cancel()
        writerJob?.cancel()
        heartbeatJob?.cancel()
        stalenessJob?.cancel()
        outbound.close()
        activity.close()
        decoder.reset()
        socket?.close()
        socket = null
        if (notify) listener?.onDisconnected(LinkDropReason.CONNECTION_LOST)
    }

    data class Options(
        val connectTimeout: Duration = DEFAULT_CONNECT_TIMEOUT,
        val writeTimeout: Duration = DEFAULT_WRITE_TIMEOUT,
        val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
        val receiveWindow: Duration = DEFAULT_RECEIVE_WINDOW,
        val heartbeat: (suspend () -> Unit)? = null,
    )

    companion object {
        val DEFAULT_CONNECT_TIMEOUT = 10.seconds
        val DEFAULT_WRITE_TIMEOUT = 5.seconds
        val DEFAULT_HEARTBEAT_INTERVAL = 30.seconds

        /**
         * How many heartbeat periods of silence the staleness watchdog tolerates.
         *
         * It must be more than one. On a quiet mesh the only inbound traffic is the heartbeat's own
         * reply, which cannot arrive before the probe that asks for it, and the manager's sync pause can
         * consume a whole tick without producing any traffic — so the longest silence a healthy link
         * shows is one interval plus a round trip. Two periods clear that with a full period to spare,
         * while a link that misses an entire probe is still dropped, which is the Swift rule (a failed
         * probe ends the link) at a wider tolerance than its 30 s interval plus 5 s command timeout.
         */
        private const val RECEIVE_WINDOW_HEARTBEAT_PERIODS = 2

        val DEFAULT_RECEIVE_WINDOW = DEFAULT_HEARTBEAT_INTERVAL * RECEIVE_WINDOW_HEARTBEAT_PERIODS

        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
    }
}

package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.connection.LinkPeripheralState
import one.meshcore.shared.connection.LinkTeardownClassification
import one.meshcore.shared.connection.ReconnectPolicy
import one.meshcore.shared.settings.PersistentLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A [MeshLink] over a single [BlePeripheral]. Mirrors the Swift `iOSBLETransport`/`BLEStateMachine`:
 *
 * - **Write path** honors the nRF52-vs-ESP32 asymmetry via [BleWriteThrottle] — the caller-requested
 *   unacknowledged write is used only when the tx characteristic advertises it, otherwise it degrades
 *   to an acknowledged Write Request paced by [writePacingMillis]. Writes drain through a single ordered
 *   channel so frame order and pacing are preserved even though [sendBytes] is a non-suspending upcall.
 * - **Read path** treats each notification as a complete frame via [BleFrameReader] (no reassembly),
 *   matching the companion protocol.
 * - **Connect** splits the iOS clocks: GATT [gattConnectTimeout] (10s) until the link is up, then
 *   [discoveryWindow] (40s, pairing dialog) once [LinkPeripheralState.CONNECTED]. A slow discovery on
 *   an already-connected link is extended a bounded number of times rather than torn down; a connected
 *   link that never finishes across the budget is classified as a bond loss.
 *
 * One instance drives one connection: [ConnectionManager] builds a fresh link per connect attempt.
 */
class BleTransport(
    private val peripheral: BlePeripheral,
    private val scope: CoroutineScope,
    options: Options = Options(),
    private val logger: PersistentLogger = PersistentLogger(scope, category = LOGGER_CATEGORY),
) : MeshLink {
    private val writePacingMillis = options.writePacingMillis
    private val policy = options.policy
    private val gattConnectTimeout = options.gattConnectTimeout
    private val discoveryWindow = options.discoveryWindow
    private val writeTimeout = options.writeTimeout
    private val deviceId = options.deviceId
    private val disconnectCleanup = BleDisconnectCleanup(peripheral, logger)
    private var listener: MeshLinkListener? = null
    private val outbound = Channel<OutboundFrame>(Channel.UNLIMITED)
    private val connectionJob = SupervisorJob(scope.coroutineContext[Job])
    private val connectionScope = CoroutineScope(scope.coroutineContext + connectionJob)
    private var writerJob: Job? = null
    private var notificationJob: Job? = null
    private var stateJob: Job? = null
    private var writeSequence: Int = 0
    private var connectedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private val queuePressure = WriteQueuePressure { depth, consecutive ->
        logger.warning("[BLE] Write queue pressure: depth=$depth, consecutive=$consecutive")
    }

    private class OutboundFrame(val bytes: ByteArray, val withoutResponse: Boolean)

    override val advertisesWriteWithoutResponse: Boolean
        get() = peripheral.advertisesWriteWithoutResponse

    override val isConnected: Boolean
        get() = peripheral.state.value == LinkPeripheralState.CONNECTED

    override fun setListener(listener: MeshLinkListener?) {
        this.listener = listener
    }

    override suspend fun connect() {
        try {
            connectWithWatchdog()
        } catch (error: CancellationException) {
            // Cancelling the awaiting coroutine does not cancel the platform GATT connect; without this
            // the radio ends up held by a link nobody monitors, and it accepts one central at a time.
            withContext(NonCancellable) { disconnectCleanup.disconnect(error) }
            throw error
        }
        logger.info("[BLE] tx writeWithoutResponse capability: $advertisesWriteWithoutResponse")
        startDropMonitor()
        startWriter()
        startNotificationPump()
    }

    override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) {
        outbound.trySend(OutboundFrame(bytes, withoutResponse))
        queuePressure.enqueue()
    }

    override suspend fun disconnect() {
        connectionJob.cancel()
        writerJob = null
        notificationJob = null
        stateJob = null
        outbound.close()
        peripheral.disconnect()
    }

    /**
     * Connects with the iOS split clocks. [peripheral.connect] is one Kable call (GATT plus discovery),
     * so the watchdog does not cancel it at 10s if the link is already up — that would abort a pairing
     * dialog. GATT that never reaches [LinkPeripheralState.CONNECTED] fails at [gattConnectTimeout];
     * a connected-but-slow discovery keeps the same in-flight connect and spends [discoveryWindow]
     * (extended while still connected). A connected link that never finishes is a suspected bond loss.
     */
    private suspend fun connectWithWatchdog() = coroutineScope {
        val connectJob = async { peripheral.connect() }
        val gattWaiter = async { peripheral.state.first { it == LinkPeripheralState.CONNECTED } }
        try {
            val reachedGatt = withTimeoutOrNull(gattConnectTimeout) {
                select {
                    connectJob.onAwait { true }
                    gattWaiter.onAwait { true }
                }
            }
            if (reachedGatt == null) {
                connectJob.cancel()
                disconnectCleanup.disconnect()
                throw MeshLinkConnectException("connect timed out in state ${peripheral.state.value}")
            }
            var extensions = 0
            while (!connectJob.isCompleted) {
                val finished = withTimeoutOrNull(discoveryWindow) {
                    connectJob.await()
                    true
                }
                if (finished == true) return@coroutineScope
                val peripheralState = peripheral.state.value
                if (policy.shouldExtendDiscoveryTimeout(peripheralState, extensions)) {
                    extensions++
                    continue
                }
                connectJob.cancel()
                disconnectCleanup.disconnect()
                if (policy.discoveryTimeoutClassification(peripheralState, extensions) ==
                    LinkTeardownClassification.BOND_LOST
                ) {
                    throw BondLostException("discovery never completed on a connected link (suspected bond loss)")
                }
                throw MeshLinkConnectException("connect timed out in state $peripheralState")
            }
            connectJob.await()
        } finally {
            gattWaiter.cancel()
        }
    }

    private fun startWriter() {
        writerJob = connectionScope.launch {
            runBleLink(
                onFailure = { error ->
                    logger.warning(bleWriteErrorLine(writeSequence, error.message ?: error.toString()))
                    disconnectCleanup.disconnect()
                },
            ) {
                var lastPacedWrite: TimeSource.Monotonic.ValueTimeMark? = null
                for (frame in outbound) {
                    val kind = BleWriteThrottle.resolveWriteKind(
                        frame.withoutResponse,
                        peripheral.advertisesWriteWithoutResponse,
                    )
                    val pacing = BleWriteThrottle.pacingDelayMillis(kind, writePacingMillis)
                    if (pacing > 0) {
                        lastPacedWrite?.let { mark ->
                            val elapsed = mark.elapsedNow().inWholeMilliseconds
                            if (elapsed < pacing) delay(pacing - elapsed)
                        }
                    }
                    val wrote = if (kind == BleWriteKind.WITHOUT_RESPONSE) {
                        writeWithoutResponse(frame.bytes)
                    } else {
                        writeWithResponse(frame.bytes)
                    }
                    if (pacing > 0) lastPacedWrite = TimeSource.Monotonic.markNow()
                    queuePressure.dequeue()
                    if (!wrote) break
                }
            }
        }
    }

    /**
     * Issues one ATT Write Command with the iOS readiness 5s backstop. A silent or hung controller
     * is a dead link: disconnect so the session does not treat the skipped frame as transmitted.
     */
    private suspend fun writeWithoutResponse(bytes: ByteArray): Boolean {
        val timeoutLine =
            "[BLE] Write-without-response readiness timeout after ${writeTimeout.inWholeSeconds}s"
        val wrote = runBleLink(
            onFailure = { error ->
                if (error is WriteWithoutResponseTimeoutException) {
                    logger.warning(timeoutLine)
                } else {
                    logger.warning(bleWriteErrorLine(writeSequence, error.message ?: error.toString()))
                }
                false
            },
        ) {
            val completed = withTimeoutOrNull(writeTimeout) {
                WriteWithoutResponseReadiness.write(timeout = writeTimeout) {
                    peripheral.write(bytes, BleWriteKind.WITHOUT_RESPONSE)
                }
                true
            }
            if (completed != true) logger.warning(timeoutLine)
            completed == true
        }
        if (!wrote) disconnectCleanup.disconnect()
        return wrote
    }

    /**
     * Issues one ATT Write Request with the iOS `claimWriteSlot` 5s backstop. A wedged controller
     * is a dead link: disconnect so later frames are not drained as if the timed-out write landed.
     */
    private suspend fun writeWithResponse(bytes: ByteArray): Boolean {
        writeSequence += 1
        val seq = writeSequence
        val wrote = runBleLink(
            onFailure = { error ->
                logger.warning(bleWriteErrorLine(seq, error.message ?: error.toString()))
                false
            },
        ) {
            WriteRequestTimeout.write(
                timeout = writeTimeout,
                onTimeout = {
                    logger.warning("[BLE] Write timeout: seq=$seq, elapsed=${writeTimeout.inWholeSeconds}s")
                },
            ) {
                peripheral.write(bytes, BleWriteKind.WITH_RESPONSE)
            }
        }
        if (!wrote) disconnectCleanup.disconnect()
        return wrote
    }

    private fun startNotificationPump() {
        notificationJob = connectionScope.launch {
            runBleLink(
                onFailure = { error ->
                    logger.warning("[BLE] Notification pump failed: ${error.message ?: error.toString()}")
                    disconnectCleanup.disconnect()
                },
            ) {
                peripheral.observeNotifications().collect { notification ->
                    BleFrameReader.frameFrom(notification)?.let { listener?.onReceived(it) }
                }
                if (peripheral.state.value == LinkPeripheralState.CONNECTED) {
                    disconnectCleanup.disconnect()
                }
            }
        }
    }

    private fun startDropMonitor() {
        stateJob = connectionScope.launch {
            // Starts after a successful connect, so a link already down when the first value arrives
            // is a drop, not a never-connected peripheral.
            var wasConnected = true
            peripheral.state.collect { peripheralState ->
                when (peripheralState) {
                    LinkPeripheralState.CONNECTED -> {
                        wasConnected = true
                        connectedAt = TimeSource.Monotonic.markNow()
                    }
                    LinkPeripheralState.DISCONNECTED ->
                        if (wasConnected) {
                            wasConnected = false
                            logger.info(
                                bleDisconnectDumpLine(deviceId, connectedAt, peripheral.lastDisconnectError()),
                            )
                            listener?.onDisconnected(peripheral.lastDropReason())
                        }
                    else -> Unit
                }
            }
        }
    }

    data class Options(
        val writePacingMillis: Long = 0,
        val policy: ReconnectPolicy = ReconnectPolicy(),
        val gattConnectTimeout: Duration = DEFAULT_GATT_CONNECT_TIMEOUT,
        val discoveryWindow: Duration = DEFAULT_DISCOVERY_WINDOW,
        val writeTimeout: Duration = DEFAULT_WRITE_TIMEOUT,
        val deviceId: String = "",
    )

    companion object {
        const val LOGGER_CATEGORY: String = "BLEStateMachine"

        /** Swift `BLEStateMachine.connectionTimeout` — GATT connect, before pairing/discovery. */
        val DEFAULT_GATT_CONNECT_TIMEOUT = 10.seconds

        /** Swift `BLEStateMachine.serviceDiscoveryTimeout` — pairing dialog after the link is up. */
        val DEFAULT_DISCOVERY_WINDOW = 40.seconds

        /** Swift `BLEStateMachine.autoReconnectDiscoveryTimeout` — bonded reconnect, no pairing dialog. */
        val AUTO_RECONNECT_DISCOVERY_WINDOW = 15.seconds
        val DEFAULT_WRITE_TIMEOUT = 5.seconds
        const val MILLIS_PER_SECOND: Double = 1000.0
    }
}

/** A connect attempt that failed before the link was established (not a bond failure). */
class MeshLinkConnectException(message: String) : Exception(message)

/** iOS `handleDidWriteValue` dump when ATT write fails and the waiter is still resumed. */
internal fun bleWriteErrorLine(seq: Int, error: String): String = "[BLE] Write error: seq=$seq, error=$error"

private fun bleDisconnectDumpLine(
    deviceId: String,
    connectedAt: TimeSource.Monotonic.ValueTimeMark?,
    disconnectError: String?,
): String {
    val elapsed = connectedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
    val error = disconnectError ?: "none"
    return bleDidDisconnectLine(
        BleDisconnectSnapshot(
            handle = deviceId.ifEmpty { "none" },
            peripheralState = "disconnected",
            isReconnecting = false,
            phase = "ready",
            elapsedSeconds = formatElapsedSeconds(elapsed / BleTransport.MILLIS_PER_SECOND),
            error = error,
        ),
    )
}

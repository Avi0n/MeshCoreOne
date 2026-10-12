// AndroidOnly: WP-303 The Bluetooth runtime link over core:ble's BleTransport (closes WP-303 C-06).
// Source behavior: MC1Services ConnectionManager.wireTransportHandlers / +BLE (disconnect, auto-reconnect, reconnect, bond-refresh
// handlers; recordBondVerification / setAppSessionLive at readiness). Android has no CoreBluetooth OS auto-reconnect (WP-205 A-06).
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.ble.BleConnectMode
import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleLinkDiagnostics
import com.meshcoreone.android.core.ble.BlePhase
import com.meshcoreone.android.core.ble.BleTransport
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.ble.BondRefresh
import com.meshcoreone.android.core.ble.FirmwareFrameCapabilities
import com.meshcoreone.android.core.connectivity.ble.BleLinkInspector
import com.meshcoreone.android.core.connectivity.ble.LinkFailureKind
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.runtime.LinkCallbacks
import com.meshcoreone.android.core.runtime.LinkFailure
import com.meshcoreone.android.core.runtime.RuntimeLink
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Remembers which radios lost an established link without a user stop, so the next link for that radio connects with
 * [BleConnectMode.Reconnect] (the source's shorter reconnect discovery budget). Thread-safe, no suspension.
 */
class BleReconnectMemory {
    private val lost = ConcurrentHashMap.newKeySet<UUID>()
    fun modeFor(deviceId: UUID): BleConnectMode = if (deviceId in lost) BleConnectMode.Reconnect else BleConnectMode.Initial
    internal fun markLost(deviceId: UUID) { lost.add(deviceId) }
    internal fun clear(deviceId: UUID) { lost.remove(deviceId) }
}

/** Maps a classified BLE failure onto the runtime's typed [LinkFailure], keeping the original as cause. */
internal fun linkFailureFor(kind: LinkFailureKind, failure: Throwable): LinkFailure = when (kind) {
    LinkFailureKind.AuthenticationFailed -> LinkFailure.AuthenticationFailed(failure)
    LinkFailureKind.ConnectionTimeout -> LinkFailure.ConnectionTimeout(failure)
    LinkFailureKind.BluetoothPoweredOff -> LinkFailure.BluetoothPoweredOff()
    LinkFailureKind.BluetoothUnavailable -> LinkFailure.BluetoothUnavailable()
    LinkFailureKind.BluetoothUnauthorized -> LinkFailure.BluetoothUnauthorized()
    LinkFailureKind.DeviceConnectedToOtherApp -> LinkFailure.DeviceConnectedToOtherApp()
    LinkFailureKind.ConnectionFailed, LinkFailureKind.DeviceNotFound -> LinkFailure.ConnectionFailed(failure.message ?: "", failure)
}

/**
 * A BLE radio link. [transport] is the session-facing view of [ble]: its `connect()` uses the connect mode this link was
 * built with, and an explicit `disconnect()` marks the stop as user intent so no loss is reported for it. Link events are
 * derived from the transport's diagnostics: leaving CONNECTED (or AUTO_RECONNECTING) after having been connected is a
 * loss, entering AUTO_RECONNECTING is `onAutoReconnecting`, and AUTO_RECONNECTING back to CONNECTED is `onReconnected`.
 * `BleTransport` does not enter AUTO_RECONNECTING itself on Android (WP-205 A-06), so those two callbacks stay dormant
 * until an owner reconnect publishes that phase. Radio identity for bond verification and the live-session flag is the
 * runtime device id, the same key the manager uses for both.
 */
class BleRuntimeLink(
    private val ble: BleTransport,
    private val deviceId: UUID,
    private val memory: BleReconnectMemory = BleReconnectMemory(),
    private val diagnostics: StateFlow<BleLinkDiagnostics> = ble.diagnostics,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onSessionLive: (SessionToken?) -> Unit = {},
) : RuntimeLink {
    private sealed interface Event {
        data class Lost(val cause: Throwable?) : Event
        data class AutoReconnecting(val details: String) : Event
        data object Reconnected : Event
    }

    private inner class Registration(val callbacks: LinkCallbacks) : AutoCloseable {
        var job: Job? = null
        override fun close() {
            val owned = synchronized(lock) { if (registration === this) { registration = null; true } else false }
            job?.cancel()
            if (owned) ble.setBondRefreshedHandler(null)
        }
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var registration: Registration? = null
    private var wasConnected = false
    private var autoReconnecting = false
    private var stoppedByUser = false
    private var lastRefresh: BondRefresh? = null

    private val session = object : MeshTransport {
        override suspend fun connect() {
            synchronized(lock) { stoppedByUser = false }
            ble.connect(memory.modeFor(deviceId))
        }
        override suspend fun disconnect() {
            synchronized(lock) { stoppedByUser = true }
            memory.clear(deviceId)
            ble.disconnect()
        }
        override suspend fun send(data: Bytes) = ble.send(data)
        override suspend fun sendWithoutResponse(data: Bytes) = ble.sendWithoutResponse(data)
        override suspend fun supportsWriteWithoutResponse(): Boolean = ble.supportsWriteWithoutResponse()
        override suspend fun supportsPipelinedReads(): Boolean = ble.supportsPipelinedReads()
        override suspend fun receivedData(): Flow<Bytes> = ble.receivedData()
        override suspend fun isConnected(): Boolean = ble.isConnected()
    }

    override val transport: MeshTransport get() = session
    override val type: TransportType = TransportType.BLUETOOTH

    /** The live diagnostics for the process-wide link inspector. */
    val currentDiagnostics: BleLinkDiagnostics get() = diagnostics.value

    override fun register(callbacks: LinkCallbacks): AutoCloseable {
        val next = Registration(callbacks)
        val previous = synchronized(lock) { registration.also { registration = next } }
        previous?.let { it.job?.cancel() }
        next.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            diagnostics.collect { observe(next, it) }
        }
        ble.setBondRefreshedHandler { refresh -> onBondRefresh(next, refresh) }
        return next
    }

    private fun observe(owner: Registration, snapshot: BleLinkDiagnostics) {
        val events = synchronized(lock) { if (registration === owner) events(snapshot) else emptyList() }
        events.forEach { deliver(owner.callbacks, it) }
    }

    /** Caller holds [lock]. */
    private fun events(snapshot: BleLinkDiagnostics): List<Event> = when (snapshot.phase) {
        BlePhase.Connected -> {
            val resumed = autoReconnecting
            autoReconnecting = false
            wasConnected = true
            if (resumed) listOf(Event.Reconnected) else emptyList()
        }
        BlePhase.AutoReconnecting -> if (autoReconnecting) emptyList() else {
            autoReconnecting = true
            listOf(Event.AutoReconnecting(snapshot.issue?.let { "error=$it" } ?: "phase=${snapshot.phase.sourceName}"))
        }
        else -> if ((wasConnected || autoReconnecting) && !stoppedByUser) {
            wasConnected = false
            autoReconnecting = false
            memory.markLost(deviceId)
            listOf(Event.Lost(snapshot.issue?.let(::causeFor)))
        } else {
            wasConnected = false
            autoReconnecting = false
            emptyList()
        }
    }

    private fun causeFor(error: BleError): Throwable {
        val raw = BleTransportException(error)
        return BleLinkInspector.classify(raw)?.let { linkFailureFor(it, raw) } ?: raw
    }

    private fun deliver(callbacks: LinkCallbacks, event: Event) = guarded {
        when (event) {
            is Event.Lost -> callbacks.onDisconnected(event.cause)
            is Event.AutoReconnecting -> callbacks.onAutoReconnecting(event.details)
            Event.Reconnected -> callbacks.onReconnected()
        }
    }

    private fun onBondRefresh(owner: Registration, refresh: BondRefresh) {
        val current = synchronized(lock) {
            if (registration !== owner) false else { lastRefresh = refresh; true }
        }
        if (current) guarded { owner.callbacks.onBondRefreshed() }
    }

    /** A callback's throw never reaches the transport or the collector; cancellation still propagates. */
    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* contained */ }
    }

    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) {
        val generation = diagnostics.value.generation
        ble.updateFirmwareCapabilities(
            generation,
            FirmwareFrameCapabilities(maximumCommandBytes = COMPANION_FRAME_BYTES, evidence = FRAME_EVIDENCE),
        )
        ble.updateWritePacing(generation, platform.recommendedWritePacing.toKotlinDuration())
    }

    override suspend fun setSessionLive(token: SessionToken?) {
        val generation = diagnostics.value.generation
        if (token != null) {
            ble.setAppSessionLive(generation, deviceId)
            onSessionLive(token)
            return
        }
        try {
            ble.setAppSessionLive(generation, null)
        } catch (gone: BleTransportException) {
            if (gone.error != BleError.NotConnected && gone.error !is BleError.StaleGeneration) throw gone
        }
        onSessionLive(null)
    }

    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) = ble.recordBondVerification(deviceId, at)

    override suspend fun clearBondVerification(deviceId: UUID) {
        synchronized(lock) { if (lastRefresh?.radioId == deviceId) lastRefresh = null }
        ble.clearBondVerification(deviceId)
    }

    /** True only while the latest RSSI refresh for [deviceId] is still the current verification of the live generation. */
    override suspend fun mayRefreshBond(deviceId: UUID): Boolean {
        val refresh = synchronized(lock) { lastRefresh } ?: return false
        return refresh.radioId == deviceId && ble.isBondRefreshCurrent(refresh)
    }

    /** Stops the link-owned collector for good (the manager closes the registration; this guards a never-registered link). */
    fun release() { scope.cancel() }

    private companion object {
        /** Companion-protocol frame ceiling (Swift PacketBuilder: "fits a 172-byte companion frame"). */
        const val COMPANION_FRAME_BYTES = 172
        const val FRAME_EVIDENCE = "MeshCore companion protocol frame limit (172 bytes), set after queryDevice"
    }
}

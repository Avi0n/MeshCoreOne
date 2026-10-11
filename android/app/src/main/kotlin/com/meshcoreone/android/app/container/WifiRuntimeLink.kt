// AndroidOnly: WP-303 The Wi-Fi/TCP runtime link and the per-connect runtime link factory (BLE link: BleRuntimeLink.kt).
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.ble.BleConfiguration
import com.meshcoreone.android.core.ble.BleDeviceHandle
import com.meshcoreone.android.core.ble.BleTransport
import com.meshcoreone.android.core.ble.GattFacade
import com.meshcoreone.android.core.connectivity.ble.BleLinkSnapshot
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransport
import com.meshcoreone.android.core.runtime.LinkCallbacks
import com.meshcoreone.android.core.runtime.RuntimeLink
import com.meshcoreone.android.core.runtime.RuntimeLinkFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * A TCP radio link. Socket loss is the only link event Wi-Fi reports (there is no auto-reconnect or bond), and it
 * reaches the runtime through the transport's disconnection handler. Registration is synchronous, as the runtime
 * requires; the transport's handler slots are lock-guarded and return immediately.
 */
class WifiRuntimeLink(host: String, port: UShort, private val wifi: WiFiTransport = WiFiTransport()) : RuntimeLink {
    override val transport: MeshTransport get() = wifi
    override val type: TransportType = TransportType.WIFI

    init { runBlocking { wifi.setConnectionInfo(host, port.toInt()) } }

    override fun register(callbacks: LinkCallbacks): AutoCloseable {
        runBlocking { wifi.setDisconnectionHandler { cause -> callbacks.onDisconnected(cause) } }
        return AutoCloseable { runBlocking { wifi.clearDisconnectionHandler() } }
    }

    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) = Unit
    override suspend fun setSessionLive(token: SessionToken?) = Unit
    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) = Unit
    override suspend fun clearBondVerification(deviceId: UUID) = Unit
    override suspend fun mayRefreshBond(deviceId: UUID): Boolean = false
}

/**
 * Builds a link per connect. Wi-Fi targets get a [WifiRuntimeLink]; Bluetooth targets get a [BleRuntimeLink] over a
 * fresh `BleTransport` whose GATT facade comes from [gattFacades] (production: `AndroidGattFacade`; JVM tests: a fake).
 * The most recent Bluetooth link is exposed through [currentBleLink] for the process-wide link inspector.
 */
class AndroidRuntimeLinkFactory(
    private val gattFacades: (BleDeviceHandle) -> GattFacade,
    private val configuration: BleConfiguration = BleConfiguration(),
    private val memory: BleReconnectMemory = BleReconnectMemory(),
    private val linkDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RuntimeLinkFactory {
    private val latest = AtomicReference<BleRuntimeLink?>(null)
    private val latestId = AtomicReference<UUID?>(null)
    private data class LiveLink(val token: SessionToken, val owner: RuntimeLink)
    private val live = AtomicReference<LiveLink?>(null)

    override fun create(target: ConnectionTarget): RuntimeLink {
        val link = when (target) {
            is ConnectionTarget.WiFi -> WifiRuntimeLink(target.host, target.port)
            is ConnectionTarget.Bluetooth -> {
                val handle = BleDeviceHandle(target.handle.address.value)
                val link = BleRuntimeLink(BleTransport(gattFacades(handle), configuration), target.deviceId, memory, dispatcher = linkDispatcher)
                latest.getAndSet(link)?.release()
                latestId.set(target.deviceId)
                link
            }
        }
        return object : RuntimeLink by link {
            override suspend fun setSessionLive(token: SessionToken?) {
                link.setSessionLive(token)
                if (token != null) live.set(LiveLink(token, link))
                else {
                    val previous = live.get()
                    if (previous?.owner === link) live.compareAndSet(previous, null)
                }
            }
        }
    }

    fun transportFor(token: SessionToken): TransportType? =
        live.get()?.takeIf { it.token == token }?.owner?.type

    /** The newest Bluetooth link's diagnostics, or null before any Bluetooth connect. */
    fun currentBleLink(): BleLinkSnapshot? {
        val link = latest.get() ?: return null
        val id = latestId.get() ?: return null
        return BleLinkSnapshot(id, link.currentDiagnostics)
    }
}
